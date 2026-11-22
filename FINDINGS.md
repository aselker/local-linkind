# Linkind / AiDot BLE — account-free setup: findings & handoff

**Status:** the BLE provisioning scheme is fully reverse-engineered and **empirically verified**
against two real bulbs (encrypted reads decrypt to valid JSON). What remains is
*implementation* — writing WiFi credentials and the post-join local control path. None of
the remaining work requires reverse engineering; it's just following the protocol documented
here. This file is self-contained: you do **not** need to read the decompiled app to continue.

---

## 1. Goal

Control two **Linkind LS01017116** RGBTW bulbs (BLE + 2.4 GHz WiFi; made by AiDot / Leedarson)
**without creating an AiDot cloud account.** Existing open-source work (`python-aidot`,
`hass-AiDot`) only controls a bulb *after* it has been set up through the cloud. The missing
piece — initial BLE provisioning done account-free — is solved below.

## 2. Hardware on the bench

| BLE address        | Adv name     | base MAC (see §4) | deviceId (`D`)                     | firmware |
|--------------------|--------------|-------------------|------------------------------------|----------|
| `AA:BB:CC:DD:EE:F2`| `AiDot-EEF0` | `AABBCCDDEEF0`    | `0123456789abcdef0123456789abcdef` | 3.13.13  |
| `AA:BB:CC:DD:EE:2A`| `AiDot-EE28` | `AABBCCDDEE28`    | `fedcba9876543210fedcba9876543210` | 3.13.13  |

Both report `F:0` in getDeviceInfo = factory / unbound. BLE advertises manufacturer ID
`0x6811`, with manufacturer data carrying the base MAC.

## 3. Where this came from (provenance / trust)

- App: **AiDot "Smart Home Life" `com.iotsolution.aidot` v2.27.1** (versionCode 4978), pulled
  as an XAPK from APKPure (VirusTotal 0/59). Package + native libs confirm OEM = **Leedarson**.
- Decompiled with `jadx` → `apk/decompiled/`. The app is a **hybrid H5 app**: native code is
  generic BLE plumbing; the device/provisioning logic lives in a React bundle
  (`assets/build.zip` → `apk/h5/main.1ebcbc27.chunk.js`).
- Key source locations (for anyone who wants to double-check):
  - Provisioning API (JS): `apk/h5/main.1ebcbc27.chunk.js` — functions `setWifiInfo`,
    `setWifiInfo2`, `bindDevice`, `getDeviceInfo`, `getDeviceId`.
  - Read/write crypto (native): `apk/decompiled/sources/com/leedarson/serviceimpl/blec075/util/b.java`
    (`BleDecryptDatasFromDevice`), `.../base/utils/a.java`, `.../blec075/h.java`.
  - Command-channel sign-in (native): `.../serviceimpl/business/BleBusinessClient.java`
    (`writeSignIn`), `.../business/bean/ClientEncryptBean.java` (`generateRandomKey`).

## 4. The GATT layout

Single vendor service **`5cfefeab-cdc1-4a6d-b1af-6f18294c99bc`**. Characteristics are the base
UUID `5cfefeab-cdc1-4a6d-b1af-6f18294cXXXX` with these `XXXX` suffixes:

| Suffix | App function     | Dir   | Encrypt     | Purpose                                            |
|--------|------------------|-------|-------------|----------------------------------------------------|
| `a00b` | `getDeviceInfo`  | read  | aes256CTR   | `{"D":deviceId,"V":fw,"F":flags,"R":..,"CRC":..}`   |
| `a00c` | (status)         | read  | aes256CTR   | nearby WiFi scan list (see §7)                      |
| `a00a` | `setWifiInfo2`   | write | aes256CTR   | join WiFi (current gen) — **the setup write**       |
| `a003` | `setWifiInfo`    | write | aes256      | join WiFi (older firmware)                          |
| `a001` | `bindDevice`     | write | aes256      | cloud bind (not needed for account-free)            |
| `a005` | `getDeviceId`    | read  | aes256      | deviceId (absent on fw 3.13.13; use `a00b` instead) |
| `b001` / `b002` | —       | w / notify | aes256CTR | runtime command channel (post-join local control)  |
| `df01` / `df02` | —       | w / notify | —      | OTA                                                 |

**base MAC** = the BLE address with the **last octet − 2** (classic ESP base-vs-BLE offset).
It equals the MAC in the advertised name / manufacturer data. `AA:BB:CC:DD:EE:F2` → `AABBCCDDEEF0`.

## 5. Encryption (verified)

One scheme covers the provisioning reads/writes:

```
key_string = "DF123EFA234569823098" + BASE_MAC_UPPERCASE_NO_COLONS     # e.g. "DF123EFA234569823098AABBCCDDEEF0"
key_bytes  = key_string.encode("ascii")[:32]  (right-padded with 0x00 to 32 if shorter)  # AES-256 key, used as RAW ASCII bytes (not hex-decoded)
cipher     = AES-256-CTR  (Java "AES/CTR/NoPadding")
```

- `"DF123EFA234569823098"` is 20 chars; a 12-hex-char MAC makes the key exactly 32 bytes.
- **aes256** (non-CTR) entries (`a001`,`a003`,`a005`) use **AES-256-CBC, PKCS7, IV = 16 zero
  bytes** (native `h.e`/`h.g`). **aes256CTR** entries use the framing below.

### aes256CTR read/write framing (the important one)

A characteristic **value** is laid out as:

```
[ 16-byte IV | ciphertext ]
```

- The **IV is the first 16 bytes**, a device-generated ASCII nonce (e.g. `b"yt9YLNXWNV8Z0QHW"`),
  sent in the clear and changing every read.
- Decrypt/encrypt the remainder with AES-256-CTR, that IV, and `key_bytes` above.

Verified: decrypting `a00b` with key `DF123EFA234569823098AABBCCDDEEF0` yields
`{"D":"0123456789abcdef0123456789abcdef","V":"3.13.13",...}`. Working code is in
`aidot_ble.py` (`decrypt_read`). For **writes** you prepend your own random 16-byte IV, then
`IV || AES256CTR(key, IV, plaintext)`. (Confirm the write-side CRC expectation — see §9 caveats.)

## 6. WiFi provisioning payloads — the account-free setup

From the JS (`main.*.chunk.js`). Encrypt with the same key and write to the characteristic.

**`setWifiInfo2` → char `a00a`, aes256CTR** (current generation):
```jsonc
{ "S": "<ssid>", "P": "<password>", "A": "<area>", "T": "<token>", "I": [channel, 0, 0, reConfig] }
```
- **Account-free form:** omit `A` and `T` — the app itself uses `{S, P, I:[channel,0,0,1]}` when
  `reConfig==1`. `T` (token) exists **only** to bind the bulb to a cloud account; leave it out.
- `channel` is the 2.4 GHz channel number (or `0`/`""`); the rest of `I` are flags.
- Optional `"H": "<host>"` overrides the server the bulb talks to (interesting for a fully-local
  or self-hosted setup — see §8).

**`setWifiInfo` → char `a003`, aes256** (older firmware, for reference): plaintext string
`"I=<ssid>;P=<password>;PL=<pwlen>;C=<channel>;S=;E="`, or for `advVersion==2` the JSON
`{S:ssid, P:password, I:[channel,0,0]}`.

**`bindDevice` → char `a001`, aes256**: cloud binding — **not needed** for account-free use.

## 7. Reading status

- **`a00b` getDeviceInfo** (aes256CTR): `{"D":deviceId, "V":firmware, "F":flags, "R":int, "CRC":int}`.
  `F:0` observed = factory/unbound. `D` is the deviceId you need for §8's command auth.
- **`a00c`** (aes256CTR): the bulb's **live 2.4 GHz scan**, `&`-separated records of
  `I=<ssid>,C=<channel>,S=..,E=..,R=<rssi>`. Lets you pick/confirm the SSID + channel
  programmatically. Observed: bulb 1 sees SSID `effective` on ch 1 @ −44 dBm, etc.

## 8. Post-join local control (command channel) — partially mapped

After the bulb is on WiFi, runtime commands go over characteristics **`b001` (write) /
`b002` (notify)** behind a **sign-in handshake** (native `BleBusinessClient.writeSignIn` +
`ClientEncryptBean.generateRandomKey`):

```
aesKey = hex( SHA256( modelId + "," + mac_lower_no_colons + "," + deviceId + "," + "nghx8qug3lkl5oyd" )[0:16] ).lower()
         # 32 hex chars -> used as 32 RAW ASCII bytes = AES-256 key
sign-in payload = "nghx8qug3lkl5oyd" || AES256CTR(key=aesKey, iv="nghx8qug3lkl5oyd", data="nghx8qug3lkl5oyd")
                  # sent as category "01", command "10"; device ACK = response cmd "90", status "00"
```
- `"nghx8qug3lkl5oyd"` is a **hardcoded constant** in the app (both IV and plaintext here).
- After auth, traffic is AES256CTR with `aesKey` and `nghx8qug3lkl5oyd` as IV.
- **Open item:** `modelId` is not in getDeviceInfo. It's a per-product string (from the AiDot
  model catalog). Find it by: (a) grepping the H5 bundle / model list in `apk/h5/` or the app's
  cloud model API, or (b) it may be fixed for the LS01017116 SKU. Everything else above is known.
- Much of the local/WiFi control protocol is already covered by **`python-aidot`** once the
  device is set up — reuse it rather than re-deriving. The `H` host override in §6 may let you
  point the bulb at a local endpoint.

## 9. What's verified vs. open

**Verified against hardware:** GATT map; base-MAC derivation; provisioning key; aes256CTR
read framing; getDeviceInfo + scan decryption on both bulbs.

**Now done (verified on hardware) — see `aidot_control.py` + `README.md`:**
- **modelId** (§8) resolved: LS01017116 (adv `shortModelId=00002a`) = **`LK.light.A001577`**,
  found by trying the bundled `LK.light.*` list until the bulb ACKs sign-in. Cached per-address.
- **Command channel** fully implemented: sign-in handshake (cat 01/cmd 10, plaintext) then
  AES-256-CTR frames (IV `nghx8qug3lkl5oyd`), and `setAttrs` (cat 03/cmd 02) TLV encoding.
  Frame = `[seq,0x11,dst,dst,cat,cmd]+payload`. Attr ids: OnOff 0028 bool, Dimming 0027,
  CCT 0038, RGBW 0056. **on/off/brightness/cct/color all confirmed driving the bulb.**

**Still untouched (not needed for BLE control):**
1. **The `setWifiInfo2` write** — join a bulb to WiFi. Needs real SSID/password; changes device
   state. Confirm the write-side CRC/length wrapper (JS sets `calculateCRC:true`) before trusting
   the first write. Note the WiFi/TCP control path (python-aidot) authenticates with
   cloud-account-derived secrets, so BLE — implemented here — is the account-free route.

## 10. Repo contents

- `aidot_ble.py` — verified: derives key from BLE address, reads+decrypts getDeviceInfo / a00c
  on both bulbs. Start here; extend it with the `setWifiInfo2` write.
- `verify_deviceinfo.py` — the key/MAC-format search harness used to pin down the scheme.
- `gatt_dump.py` — dumps the full GATT table of a device.
- `apk/decompiled/` — jadx output. `apk/h5/` — the React bundle. `tools/jadx/` — decompiler.
- Python deps in `.venv` (`bleak`, `cryptography`). Run e.g. `.venv/bin/python aidot_ble.py AA:BB:CC:DD:EE:F2`.

## 11. One-paragraph summary for a fresh session

Two factory Linkind LS01017116 (AiDot/Leedarson) bulbs are on BLE. To set one up on WiFi with no
cloud account: connect to service `5cfefeab-cdc1-4a6d-b1af-6f18294c99bc`; derive the key
`"DF123EFA234569823098"+BASE_MAC_UPPER` where BASE_MAC = BLE address minus 2 in the last octet;
AES-256-CTR with IV = first 16 bytes of any read value. Read `a00b` for the deviceId and `a00c`
for the WiFi scan, then write `a00a` (`setWifiInfo2`) with `{"S":ssid,"P":password,"I":[channel,0,0,1]}`
encrypted the same way (random 16-byte IV prefix; verify the CRC wrapper first). Omit the `T`
token — it only binds to the cloud. Post-join control reuses `python-aidot`; the only loose end
is the per-product `modelId` string for the optional local command-channel sign-in. All of this
is verified except the writes themselves.
