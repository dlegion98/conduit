# Conduit wire protocol (v1)

The Mac app **listens**; the Android app **connects**. Everything runs over plain TCP
on the local network, end-to-end encrypted with a key exchanged by QR code.

## Pairing

The Mac generates a random 32-byte key `K` and shows a QR code:

```
conduit://pair?v=1&id=<macId>&name=<urlencoded mac name>&port=<port>&hosts=<ip1,ip2>&key=<base64url K, no padding>
```

The phone stores `id`, `name`, `port`, `hosts` and `K`. The Mac stores `K` in the Keychain.
Scanning the QR code in person is the trust anchor: anyone without `K` cannot complete a
handshake.

## Discovery

The Mac advertises Bonjour service `_conduit._tcp` with TXT record `id=<macId>`.
The phone browses with `NsdManager`, picks the service whose `id` matches, and falls back
to the `hosts` from the QR code.

## Connection handshake (plaintext)

1. Client → server, 38 bytes: `"CNDT"` · `0x01` (version) · channel byte · 32 random bytes `Rc`
2. Server → client, 37 bytes: `"CNDT"` · `0x01` · 32 random bytes `Rs`

Channels: `0` control · `1` screen mirror · `2` camera · `3` file transfer

Keys (HKDF-SHA256, 32-byte output, `salt = Rc || Rs`, `ikm = K`):
- `info = "conduit-v1 c2s"` → phone→Mac key
- `info = "conduit-v1 s2c"` → Mac→phone key

## Frames (encrypted)

```
uint32 BE length L | L bytes of AES-256-GCM ciphertext || 16-byte tag
```

- Nonce (implicit, never sent): 4 zero bytes · uint64 BE counter. Each direction keeps its
  own counter, starting at 0 and going up by 1 each frame. Replayed, reordered or dropped
  frames fail to decrypt, and the connection closes.
- Plaintext: 1 byte kind (`0` = UTF-8 JSON, `1` = binary) · payload.
- Max L = 16 MiB.

The first client frame is `{"type":"auth","deviceId":"…","deviceName":"…"}`. The server
answers `{"type":"auth-ok"}`. If a frame fails to decrypt, the key is wrong and the socket
closes.

## Control channel messages (JSON, field `type`)

### Phone → Mac
| type | fields |
|---|---|
| `hello` | `deviceName, model, appVersion` |
| `status` | `battery` (0-100), `charging` |
| `notification` | `key, pkg, app, title, text, time, icon?` (b64 PNG), `actions:[{index,title,reply}]` |
| `notification-removed` | `key` |
| `clipboard` | `text?` · `image?` (b64 PNG) |
| `sms-threads` | `threads:[{threadId,address,name,snippet,date,read}]` |
| `sms-messages` | `threadId, messages:[{id,address,body,date,outgoing}]` |
| `sms-received` | `threadId, address, name, body, date` |
| `sms-sent` | `address, ok, error?` |
| `call` | `state` (`ringing`/`offhook`/`idle`), `number, name` |
| `media` | `active, app, title, artist, album, playing, positionMs, durationMs, art?` (b64 JPEG) |
| `open-url` | `url` |
| `mac-input` | `kind` (`move`,`drag`,`down`,`up`,`click`,`rightclick`,`doubleclick`,`scroll`,`text`,`key`,`media`), `dx, dy, text, key` |
| `ring-state` | `ringing` |
| `error` | `feature, message` |
| `ping` / `pong` | — |

### Mac → Phone
| type | fields |
|---|---|
| `hello` | `deviceName` |
| `mac-status` | `battery, charging` |
| `notification-action` | `key, index, text?` |
| `notification-dismiss` | `key` |
| `clipboard` | `text?` · `image?` |
| `sms-list-threads` | — |
| `sms-list-messages` | `threadId` |
| `sms-send` | `address, body` |
| `call-action` | `action` (`answer`/`decline`) |
| `call-dial` | `number` |
| `media-control` | `action` (`toggle`,`play`,`pause`,`next`,`previous`) |
| `open-url` | `url` |
| `file-offer` | `id, name, size` |
| `ring` | `on` |
| `mirror-start` | `maxSize, bitrate, fps` · `mirror-stop` |
| `camera-start` | `facing` (`back`/`front`), `width, height, fps` · `camera-stop` |
| `input` | `action` (`tap`,`longpress`,`gesture`,`global`,`text`,`backspace`,`enter`), `x, y` (0-1), `points:[[x,y],…]`, `durationMs`, `key` (`back`,`home`,`recents`,`notifications`,`quicksettings`,`lock`,`screenshot`), `text` |
| `ping` / `pong` | — |

## Video channels (1 = mirror, 2 = camera)

After auth the phone sends `{"type":"video-format","width":W,"height":H}`, followed by binary frames:

```
flags (1 byte: bit0 = codec config SPS/PPS, bit1 = keyframe) | int64 BE ptsUs | H.264 Annex-B data
```

A new `video-format` (for example after folding or rotating) is followed by fresh codec config.

## File channel (3)

- **Phone → Mac:** `{"type":"file-push","id","name","size"}`, then binary chunks, then
  `{"type":"file-end","id"}`. The Mac replies `{"type":"file-done","id"}`.
- **Mac → Phone:** the Mac sends `file-offer` on control. The phone opens a file channel and sends
  `{"type":"file-pull","id"}`. The Mac sends binary chunks, then `{"type":"file-end","id"}`.
