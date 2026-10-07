import Foundation

/// Trust for devices that dial the Mac (reverse connect).
///
/// A device's install id is public (it is in its Bonjour record), so it cannot
/// vouch for itself. The first time a person allows a device, the Mac gives it
/// a random token; the device presents the token on every later connection and
/// is let in without asking. Anything without a matching token is asked about.
enum ReversePairing {
    enum Decision: Equatable {
        case admit
        case ask
    }

    /// 32 random bytes, base64.
    static func newToken() -> String {
        var generator = SystemRandomNumberGenerator()
        let bytes = (0..<32).map { _ in UInt8.random(in: .min ... .max, using: &generator) }
        return Data(bytes).base64EncodedString()
    }

    /// Compares without stopping at the first difference, so timing says
    /// nothing about how much of a guess was right.
    static func matches(_ presented: String?, _ stored: String?) -> Bool {
        guard let presented, let stored, !stored.isEmpty else { return false }
        let a = Array(presented.utf8)
        let b = Array(stored.utf8)
        var difference = a.count ^ b.count
        for i in 0..<max(a.count, b.count) {
            difference |= Int(i < a.count ? a[i] : 0) ^ Int(i < b.count ? b[i] : 0)
        }
        return difference == 0
    }

    /// `paired` maps a device's install id to the token it was given.
    static func decide(deviceID: String?, presentedToken: String?,
                       paired: [String: String]) -> Decision {
        guard let deviceID, matches(presentedToken, paired[deviceID]) else { return .ask }
        return .admit
    }
}
