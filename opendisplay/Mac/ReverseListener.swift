import Foundation
import Network

/// Accepts connections from receivers that dial the Mac ("reverse connect").
///
/// Some networks let a device reach the Mac but drop the Mac's own dial to the
/// device (AP client isolation, guest Wi-Fi, a VPN kill switch). The Android
/// receiver finds this listener through Bonjour and connects out instead; over
/// a cable `adb reverse tcp:9011 tcp:9011` makes the same listener reachable
/// as 127.0.0.1. Once the TCP link is up the stream protocol is unchanged.
/// Constants shared with the Android receiver (`WireProtocol.MAC_REVERSE_PORT`
/// and `MAC_HOST_SERVICE_TYPE`); outside the listener so any thread can read them.
enum ReverseConnect {
    static let port: UInt16 = 9011
    static let serviceType = "_opendisplay-mac._tcp"
}

@MainActor
final class ReverseListener {
    private static let port = ReverseConnect.port
    private static let serviceType = ReverseConnect.serviceType

    /// Called on the main actor with each accepted connection (not yet started).
    var onConnection: ((NWConnection) -> Void)?

    private var listener: NWListener?
    private var restartWork: DispatchWorkItem?

    func start() {
        guard listener == nil else { return }
        let options = NWProtocolTCP.Options()
        options.noDelay = true   // latency matters more than throughput here
        let parameters = NWParameters(tls: nil, tcp: options)
        parameters.allowLocalEndpointReuse = true
        parameters.serviceClass = .interactiveVideo
        guard let port = NWEndpoint.Port(rawValue: Self.port),
              let listener = try? NWListener(using: parameters, on: port) else {
            Log.info("reverse listener could not bind :\(Self.port) — retrying")
            scheduleRestart()
            return
        }
        listener.service = NWListener.Service(
            name: Host.current().localizedName ?? "Mac", type: Self.serviceType)
        listener.newConnectionHandler = { [weak self] connection in
            Task { @MainActor in self?.onConnection?(connection) }
        }
        listener.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                Log.info("reverse listener ready on :\(Self.port)")
            case .failed(let error):
                Log.info("reverse listener failed: \(error) — restarting")
                Task { @MainActor in
                    self?.listener?.cancel()
                    self?.listener = nil
                    self?.scheduleRestart()
                }
            default:
                break
            }
        }
        listener.start(queue: .main)
        self.listener = listener
    }

    func stop() {
        restartWork?.cancel()
        restartWork = nil
        listener?.cancel()
        listener = nil
    }

    private func scheduleRestart() {
        restartWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            Task { @MainActor in self?.start() }
        }
        restartWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 5, execute: work)
    }

    /// The peer's address without any `%interface` scope, e.g. `192.168.1.5`
    /// or `127.0.0.1` for an `adb reverse` link.
    static func peerHost(of connection: NWConnection) -> String {
        guard case .hostPort(let host, _) = connection.endpoint else { return "unknown" }
        return Self.stripScope("\(host)")
    }

    static func stripScope(_ address: String) -> String {
        address.split(separator: "%", maxSplits: 1).first.map(String.init) ?? address
    }
}
