# Android polish change ledger

115 runtime changes and 48 added regression tests (163 entries total). No device performance benchmark is claimed.

## AudioPacket.kt / AudioPlayer.kt

1. Validate AUD1 headers before touching AudioTrack.
2. Reject unsupported channel layouts instead of coercing and misplaying PCM.
3. Reject sample rates below 8 kHz instead of playing at a different rate.
4. Read the frame count as an unsigned value using Long arithmetic.
5. Require PCM byte counts to match the packet, preventing overflow and truncated writes.
6. Serialize audio feeding, stopping, and releasing to prevent track teardown races.
7. Recreate the track after negative AudioTrack write results or write exceptions.
8. Retain the track before play so failed startup can release it.
9. Check that AudioTrack initialized before playing.
10. Clear the track reference before teardown and release it even if pause or flush throws.

## net/FrameCodec.kt

11. Reject empty outbound frames, matching inbound framing rules.
12. Remove ByteBuffer wrapper allocations from frame header encoding and decoding.
13. Handle zero-progress InputStream reads without an infinite loop.

## video/AnnexBParser.kt / net/ReceiverServer.kt

14. Accept JSON clipboard controls up to the existing 1 MiB wire ceiling instead of misclassifying them as video.
15. Limit metadata string decoding to 4 KiB rather than copying arbitrary binary payloads into strings.
16. Decode video metadata only when it starts with a JSON object.
17. Accept whitespace before the metadata colon.
18. Parse exponent notation and signed numeric metadata correctly.
19. Discard infinite metadata values.
20. Ignore payloads with no video NALUs instead of reporting a stream.
21. Wait for decoder input before declaring streaming; parameter sets alone are insufficient.

## ui/CursorOverlayView.kt

22. Reuse the fallback cursor Path instead of allocating during every draw.
23. Coalesce cursor redraws with the display animation clock.
24. Skip redundant cursor position updates.
25. Ignore nonfinite cursor positions.
26. Bound encoded cursor sprite size before Base64 decoding.
27. Validate sprite dimensions and hotspot coordinates before drawing.
28. Inspect PNG bounds before pixel allocation, limiting decoded sprites to 512 pixels per edge.
29. Let bitmap lifetime follow renderer references instead of recycling bitmaps still referenced by hardware draw lists.
30. Exclude the decorative cursor overlay from accessibility navigation.

## input/TextForwarder.kt / ui/KeyboardCaptureView.kt

31. Trim composing text with one substring operation instead of repeated allocations per deleted character.
32. Translate UTF-16 IME deletion into code point deletions without splitting emoji.
33. Support the IME code-point deletion API.
34. Return false for unsupported forward deletion instead of falsely claiming success.
35. Reject negative IME deletion lengths.
36. Disable personalized IME learning for text forwarded to the desktop.
37. Defer keyboard display until the view is focused and attached.
38. Finish composition when explicitly hiding the keyboard.

## input/DesktopPointer.kt / InputControls.kt / HardwareKeyForwarder.kt

39. Reject invalid absolute pointer positions before corrupting pointer state.
40. Reject nonfinite relative motion.
41. Reject nonfinite wheel values.
42. Ignore invalid contact counts and nonfinite gesture motion before updating gesture accumulators.
43. Recover invalid saved pointer speed to the default.
44. Recover nonfinite pointer speed updates to the default.
45. Compute other held modifiers without allocating a filtered map on every key event.

## video/H264Decoder.kt

46. Ignore surface changes after decoder release and redundant null surface changes.
47. Reset keyframe throttling between sessions.
48. Clear old decode timestamps and latency samples between sessions.
49. Release the fallback decoder when plain configuration or startup fails.
50. Release the primary decoder when startup fails.
51. Continue the plain-format fallback even when releasing the rejected codec throws.
52. Recover and request a keyframe if an acquired input buffer is unexpectedly missing.
53. Use monotonic elapsed time for keyframe throttling.
54. Permit the first keyframe request immediately after reset.
55. Ignore negative presentation timestamps when indexing the latency ring.
56. Exclude infinite percentile samples and clamp the percentile before multiplication.

## net/WifiNetworkHolder.kt / ReceiverServer.kt

57. Keep Wi-Fi selection local to stream sockets instead of rebinding every connection in the app process.
58. Allow local-only Wi-Fi networks that do not advertise Internet access.
59. Serialize Wi-Fi holder startup and shutdown.
60. Ignore network callbacks belonging to an old registration.
61. Clear a lost network atomically without clearing a replacement network.
62. Restore the interruption flag when a network wait is interrupted.
63. Resolve outbound hosts through the selected Wi-Fi network.
64. Apply a two-second timeout to LAN TCP connection attempts.
65. Close failed LAN and loopback connection sockets.
66. Close a socket after a failed write so session cleanup and reconnect can proceed.
67. Close and drain the outgoing queue on shutdown to release retained JSON messages.
68. Remove pending server UI callbacks on shutdown.
69. Reject nonfinite ping timestamps before clock synchronization.
70. Only accept known mirror/extend welcome modes.
71. Reject nonfinite incoming cursor positions before posting UI callbacks.
72. Reject disconnected or nonfinite outgoing touch and pen samples.
73. Reject nonfinite click coordinates.
74. Reject disconnected, zero-delta, or nonfinite scroll events.

## net/MacAddress.kt / PairTokens.kt

75. Bound manually entered host input before attempting DNS.
76. Reject URL components and control characters in host input.
77. Reject malformed brackets and bracketed non-IPv6 host syntax.
78. Require unsigned decimal port text.
79. Reject blank pairing identifiers and tokens.
80. Validate persisted tokens before presenting them to a host.

## clip/ImageClip.kt / MainActivity.kt

81. Only read clipboard images through content URIs.
82. Handle inaccessible MIME providers and missing clipboard MIME entries.
83. Allow small images below 16 pixels to sync.
84. Return failure when PNG compression fails.
85. Validate inbound clipboard image size, PNG type, and decoded dimensions before storing.
86. Check clipboard cache directory creation.
87. Write received clipboard images atomically so failed writes preserve the previous file.
88. Replace unmanaged clipboard worker threads with lifecycle-owned coroutines.
89. Cancel superseded clipboard image work and check cancellation before publishing its result.
90. Serialize received clipboard file writes.
91. Make the shared image deduplication hash visible across threads.

## update/UpdateChecker.kt

92. Validate APK filenames again at the download entry point.
93. Check APK size before writing each new chunk to disk.
94. Honor thread interruption during download.
95. Reject empty or content-length-mismatched downloads.
96. Bound metadata reads to 1 MiB even without a Content-Length header.
97. Disconnect HTTP connections when connection setup or response-code retrieval fails.

## net/NsdAdvertiser.kt

98. Validate the service port before acquiring advertising resources.
99. Serialize advertising registration and teardown.
100. Ignore stale registration success callbacks.
101. Ignore stale registration failure callbacks instead of resurrecting advertisements.
102. Release advertising resources after exhausted retries or registration exceptions.

## MainActivity.kt / ReceiverForegroundService.kt / ui settings / AndroidManifest.xml

103. Release activity-owned receiver resources on configuration recreation.
104. Clear IME composition on input focus loss.
105. Prevent text and backspace forwarding when streaming is inactive.
106. Hold the screen awake while connected instead of draining battery on the idle connection screen.
107. Avoid restarting an orphan foreground service after process death.
108. Make foreground notifications private, suppress repeated alerts, and remove the misleading timestamp.
109. Make desktop settings switch rows one labeled accessible toggle with a larger tap area.
110. Make the auto-update setting row one labeled accessible toggle.
111. Disable update checking during all busy update states.
112. Disable autocorrect for manually entered host addresses.
113. Disable manual Connect for blank addresses.
114. Remove the unnecessary optional Leanback feature declaration that triggered a TV launcher lint error; this does not require TV hardware.
115. Document and narrowly suppress the inherited dispatchKeyEvent library-restriction lint false positive.

## Added regression tests

116. `FrameCodecTest.kt`: `cannotWriteEmptyFrame`.
117. `FrameCodecTest.kt`: `cannotReadEmptyFrame`.
118. `FrameCodecTest.kt`: `oversizedHeaderRejectedBeforeAllocation`.
119. `FrameCodecTest.kt`: `unsignedHugeHeaderRejected`.
120. `FrameCodecTest.kt`: `partialHeadersThrow`.
121. `FrameCodecTest.kt`: `truncatedBodyThrows`.
122. `FrameCodecTest.kt`: `zeroProgressReadDoesNotSpin`.
123. `FrameCodecTest.kt`: `consecutiveFramesKeepBoundaries`.
124. `FrameCodecTest.kt`: `percentileIgnoresInfinity`.
125. `FrameCodecTest.kt`: `percentileClampsBeforeMultiplying`.
126. `MacAddressTest.kt`: `rejectsUrlComponentsAndControlCharacters`.
127. `MacAddressTest.kt`: `rejectsMalformedBracketsAndSignedPorts`.
128. `MacAddressTest.kt`: `rejectsUnboundedInput`.
129. `PairingTest.kt`: `whitespaceTokensAreIgnored`.
130. `PairingTest.kt`: `corruptStoredTokensAreNotPresented`.
131. `DesktopPointerTest.kt`: `invalidPositionDoesNotPoisonFutureMotion`.
132. `DesktopPointerTest.kt`: `invalidMotionIsIgnored`.
133. `DesktopPointerTest.kt`: `invalidScrollIsIgnored`.
134. `DesktopPointerTest.kt`: `invalidContactDoesNotPoisonGesture`.
135. `DesktopPointerTest.kt`: `nonfinitePointerSpeedUsesDefault`.
136. `TextForwarderTest.kt`: `utf16DeletionTreatsEmojiAsOneKey`.
137. `TextForwarderTest.kt`: `utf16DeletionNeverLeavesHalfEmoji`.
138. `TextForwarderTest.kt`: `deletionBeyondCompositionIncludesEarlierText`.
139. `TextForwarderTest.kt`: `bulkDeletionClearsComposition`.
140. `TextForwarderTest.kt`: `nonpositiveDeletionIsIgnored`.
141. `AnnexBParserTest.kt`: `largeClipboardControlIsNotVideo`.
142. `AnnexBParserTest.kt`: `controlFrameOverLimitIsRejected`.
143. `AnnexBParserTest.kt`: `metadataAllowsWhitespaceBeforeColon`.
144. `AnnexBParserTest.kt`: `exponentMetadataIsNotTruncated`.
145. `AnnexBParserTest.kt`: `infiniteMetadataIsDiscarded`.
146. `AnnexBParserTest.kt`: `oversizedMetadataIsNotDecoded`.
147. `AudioPacketTest.kt`: `mono`.
148. `AudioPacketTest.kt`: `stereo`.
149. `AudioPacketTest.kt`: `truncatedHeaders`.
150. `AudioPacketTest.kt`: `badMagic`.
151. `AudioPacketTest.kt`: `unknownVersion`.
152. `AudioPacketTest.kt`: `zeroChannels`.
153. `AudioPacketTest.kt`: `unsupportedChannels`.
154. `AudioPacketTest.kt`: `unsignedChannels`.
155. `AudioPacketTest.kt`: `zeroRate`.
156. `AudioPacketTest.kt`: `tooLowRate`.
157. `AudioPacketTest.kt`: `minimumRate`.
158. `AudioPacketTest.kt`: `unsignedRate`.
159. `AudioPacketTest.kt`: `zeroFrames`.
160. `AudioPacketTest.kt`: `unsignedFrameCount`.
161. `AudioPacketTest.kt`: `multiplicationOverflow`.
162. `AudioPacketTest.kt`: `truncatedBody`.
163. `AudioPacketTest.kt`: `trailingBytes`.

## Validation

- `./gradlew testDebugUnitTest assembleDebug --offline --console=plain`: passed; 148 tests, zero failures/errors.
- `./gradlew lintDebug --offline --console=plain`: passed; zero errors, 52 warnings. The lint SARIF quick-fix reporter printed UseKtx edit-generation exceptions but still generated the reports successfully.
- `git diff --check`: passed.
- Debug APK: `app/build/outputs/apk/debug/OpenDisplay-3.7.4-debug.apk` (also copied to `/home/ubuntu/OpenDisplay-3.7.4-debug.apk`).
- No device/emulator testing or measured frame-rate, latency, thermal, or battery benchmarks. Wi-Fi binding/DNS, AudioTrack/MediaCodec recovery, activity recreation, IME behavior, and accessibility changes need device smoke testing.
