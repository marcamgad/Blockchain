# Running HybridChain on a Raspberry Pi — Complete Beginner's Guide

> ## ⛔ Read this first — current status (2026-10-09)
> A security audit (`docs/audit/HYBRIDCHAIN_AUDIT.md`) ran the real jar and found bugs that
> stop it working as a **network**:
> - **Nodes cannot connect to each other** (HC-005) — so a multi-Pi network will not form.
> - **IoT sensor readings are always rejected** (HC-004).
> - A 4-node network can't send messages without a workaround flag (HC-001), and 5- or 6-node
>   networks are unsafe (HC-002).
>
> **What works today:** a single Pi starts, creates its genesis block, opens its ports, and
> answers API calls (Tests 1–6 below). Treat this guide as "get one node running and learn
> the setup". **Don't buy more Pis for a network until HC-001, HC-002, HC-004 and HC-005 are
> fixed** — each has a ready patch and a test in the audit report.

**This guide assumes you know nothing.** Every command is copy-paste. After each step it
tells you what you should see, and what to do if you see something else.

---

## 0. What is this, in plain language?

HybridChain is a **blockchain for IoT devices**. Think of it as a shared notebook that many
small computers write into, where nobody can secretly erase or change an old page.

You are going to put that software onto a **Raspberry Pi** — a small, cheap computer the
size of a credit card — and have it join the network as a **node** (one participant).

Three words you'll see constantly:

| Word | Plain meaning |
|---|---|
| **Node** | One computer taking part in the network. Your Pi will be one. |
| **Validator** | A node that helps decide which transactions are official. Does heavy work. |
| **Gateway** | A node that collects sensor readings and forwards them. Does light work. |

---

## 1. Can a Raspberry Pi 3 actually run this? — **I measured it. Here's the honest answer.**

**Short answer: Yes, as a Gateway or Observer node. Not recommended as a Validator. And you
must leave the zero-knowledge privacy features switched OFF.**

I ran the real code to check, rather than guessing:

### Memory — ✅ passes
The project's own test suite normally asks for 2 GB of memory, which a Pi 3 (1 GB total)
does not have. So I re-ran the core of the system capped at **384 MB**:

> 67 tests passed at `-Xmx384m` — including storage, smart contracts, post-quantum
> signatures and zero-knowledge proofs.

So the node itself fits comfortably in a Pi 3's memory. Good.

### Speed — ⚠️ mostly fine, with **one serious exception**
Measured per-operation on a fast desktop PC (`EdgeReadinessProbe`):

| Operation | Desktop PC | Estimated Pi 3 (10–20× slower)¹ | Verdict on Pi 3 |
|---|---|---|---|
| SHA-256 hash (1 KB) | 0.012 ms | ~0.1–0.2 ms | ✅ fine |
| ECDSA sign (normal transaction) | 1.00 ms | ~10–20 ms | ✅ fine |
| ECDSA verify | 0.71 ms | ~7–14 ms | ✅ fine |
| Post-quantum sign (Dilithium) | 4.00 ms | ~40–80 ms | ✅ acceptable |
| Post-quantum verify | 0.95 ms | ~10–19 ms | ✅ fine |
| **Zero-knowledge proof — create** | **5 213 ms** | **~52–104 seconds** | ❌ **unusable** |
| **Zero-knowledge proof — verify** | **3 887 ms** | **~39–78 seconds** | ❌ **unusable** |

¹ The Pi column is an **estimate**, not a measurement — I do not have a Pi to test on. It
assumes a Pi 3's in-order 1.2–1.4 GHz Cortex-A53 core is 10–20× slower than a modern desktop
core on this kind of big-integer maths. **Run `EdgeReadinessProbe` on your actual Pi to
replace these estimates with real numbers** (instructions in §8.3).

**What this means practically:** the zero-knowledge features (`ZkEligibilityGate`,
`PRIVATE_TRANSFER` transactions) take **over 5 seconds per proof on a powerful desktop**.
That is a property of the current hand-written proof code, not of the Pi. On a Pi 3 it would
be roughly a minute per proof, which is not usable. **Leave those features off** (they are
off by default — §6 shows the settings). Everything else runs fine.

### Compatibility — ✅ passes
The only piece of the software that is compiled for a specific chip type is the LevelDB
database driver. I checked the source: if the chip-specific version fails to load, the code
automatically falls back to a pure-Java version that works on any chip, including ARM:

```java
try   { factory = JniDBFactory.factory; }      // fast, chip-specific
catch (Throwable t) { factory = Iq80DBFactory.factory; }   // works everywhere (slower)
```

So a Pi will work. It will just be a bit slower at disk access.

### My recommendation
- **Raspberry Pi 3** — OK for learning, testing, and as a Gateway/Observer node.
- **Raspberry Pi 4 (4 GB) or Pi 5** — what I'd actually buy. Roughly 3× the CPU and 4× the
  memory for a small extra cost, and it can serve as a Validator.
- If you already own a Pi 3, **start with it.** Don't buy anything until you've done §8.

---

## 2. What you need to buy

| Item | Minimum | Recommended | Why |
|---|---|---|---|
| Raspberry Pi | Pi 3 Model B (1 GB) | **Pi 4, 4 GB** | Memory is the tight constraint |
| microSD card | 16 GB, Class 10 | **32 GB A2-rated** | The blockchain grows over time; slow cards make everything slow |
| Power supply | 5 V 2.5 A official | 5 V 3 A (Pi 4: USB-C) | Underpowered Pis corrupt SD cards |
| Network | Wi-Fi | **Ethernet cable** | Far more reliable for a node that must stay reachable |
| Case with heatsink | optional | **yes** | The Pi will run warm continuously |
| A normal computer | any | any | To write the SD card and copy files over |

Total: roughly £50–80 / $60–95 for a Pi 4 setup.

---

## 3. Step 1 — Put an operating system on the SD card

1. On your normal computer, download **Raspberry Pi Imager**:
   <https://www.raspberrypi.com/software/>
2. Insert the microSD card.
3. Open Imager and choose:
   - **Device:** your Pi model
   - **Operating System:** `Raspberry Pi OS (other)` → **`Raspberry Pi OS Lite (64-bit)`**
     - *"Lite" = no desktop. You don't need one, and it saves memory.*
     - **It must be 64-bit.** Java 17 works best on 64-bit. A Pi 3 supports 64-bit.
   - **Storage:** your SD card
4. Click the **gear icon** (⚙, advanced settings) and set:
   - ☑ Enable SSH → *"Use password authentication"*
   - Username: `pi`, and a password you'll remember — **write it down**
   - ☑ Configure Wi-Fi (only if you're not using an Ethernet cable)
   - Set your country/locale
5. Click **Write** and wait (about 5–10 minutes).

> **What "SSH" means:** it lets you type commands on the Pi from your normal computer, so
> the Pi doesn't need its own screen or keyboard.

---

## 4. Step 2 — Start the Pi and connect to it

1. Put the SD card in the Pi, plug in the Ethernet cable (if using), then plug in power.
2. Wait 2 minutes for it to start up.
3. On your normal computer open a terminal:
   - **Windows:** press `Win+R`, type `powershell`, press Enter
   - **Mac/Linux:** open Terminal
4. Connect:

```bash
ssh pi@raspberrypi.local
```

Type `yes` if asked about a fingerprint, then your password.

**You should see** something ending in `pi@raspberrypi:~ $`. That's the Pi's command line.

**If `raspberrypi.local` doesn't work:** find the Pi's IP address in your router's admin
page (look for a device called `raspberrypi`), then use `ssh pi@192.168.1.42` with that
number instead.

---

## 5. Step 3 — Install Java on the Pi

Copy-paste these one at a time:

```bash
sudo apt update
sudo apt upgrade -y
sudo apt install -y openjdk-17-jdk-headless
```

That last one takes a few minutes. Check it worked:

```bash
java -version
```

**You should see** something containing `openjdk version "17`. If you see
`command not found`, the install failed — re-run the `apt install` line.

### Free up memory (important on a Pi 3)
The Pi reserves memory for graphics that a headless node never uses. Reclaim it:

```bash
echo "gpu_mem=16" | sudo tee -a /boot/firmware/config.txt
sudo reboot
```

*(On older Raspberry Pi OS the file is `/boot/config.txt` — if the command above errors,
try that path.)* Wait a minute, then `ssh` back in.

---

## 6. Step 4 — Copy the software onto the Pi

You need the file `blockchain-java-1.0-SNAPSHOT-jar-with-dependencies.jar` (about 47 MB).
It's produced by building the project on your normal computer with:

```bash
mvn package -DskipTests
```

…and it lands in the `target/` folder.

### Upload it — pick ONE method

**Method A — `scp` (simplest, works everywhere).**
Run this **on your normal computer**, not on the Pi:

```bash
scp target/blockchain-java-1.0-SNAPSHOT-jar-with-dependencies.jar pi@raspberrypi.local:/home/pi/
```

Enter the password. You'll see a progress bar. On Windows PowerShell the same command works.

**Method B — a USB stick.** Copy the `.jar` onto a USB stick, plug it into the Pi, then:

```bash
sudo mkdir -p /mnt/usb && sudo mount /dev/sda1 /mnt/usb
cp /mnt/usb/blockchain-java-*.jar /home/pi/
```

**Method C — download it directly on the Pi** (if the file is on a server or GitHub
release):

```bash
wget -O /home/pi/hybridchain.jar https://your-url-here/blockchain-java.jar
```

### Confirm it arrived (run this **on the Pi**)

```bash
ls -lh /home/pi/*.jar
```

**You should see** a file of about `47M`.

---

## 7. Step 5 — Configure the node

> **⚠️ Correction (2026-10-09).** An earlier version of this section was wrong in five ways
> that would have stopped your node from working. I found them by actually starting the jar,
> which I should have done before writing the guide:
> 1. It omitted `VALIDATOR_PUBKEYS`, without which the node **crashes on startup**
>    (`PBFT requires at least 4 validators`).
> 2. It said the API is on port 8000. It is on **8080** — `API_PORT` is never read by the web
>    server. Use `SERVER_PORT` to change it.
> 3. It told you to generate a fresh `STORAGE_AES_KEY` on each node. Nodes with different
>    values **cannot connect to each other** (the TLS certificate authority is derived from
>    this key). Every node in one network currently must use the **same** value.
> 4. A network of exactly 4 validators **cannot produce blocks** without an extra flag
>    (`-DMIN_PEERS=3`, see §8). This is a bug, tracked as HC-001.
> 5. It linked `REQUIRE_QUANTUM_SIG` to zero-knowledge proofs. It controls post-quantum
>    (Dilithium) signatures, and **must be identical on every node**.

### 7.1 Understand the network first

This is a *Byzantine-fault-tolerant* blockchain: it needs **at least 4 validator nodes** that
all know each other's public keys before any of them start. A single Pi on its own **will
start and answer API requests, but will never create a block** — there's nobody to vote
with. That's expected, not a fault.

So you decide your validator set up front, on **one** computer, and give every node the same
list.

### 7.2 Generate the keys — once, on your normal computer

Run this in the project folder (it uses the jar you built):

```bash
java -cp target/blockchain-java-1.0-SNAPSHOT-jar-with-dependencies.jar \
     com.hybrid.blockchain.tools.KeygenTool 4 "$(openssl rand -hex 32)"
```

**The random value at the end is essential.** `KeygenTool` derives keys from that seed. If
you leave it out, it uses the built-in default `hybridchain-key-seed` — and then **anyone in
the world can recompute your validators' private keys**. Always pass a random seed.

It prints something like:

```text
NODE_1_PRIVATE_KEY=2321…      <- secret, goes ONLY on node 1
NODE_1_PUBLIC_KEY=031c…
NODE_2_PRIVATE_KEY=…          <- secret, goes ONLY on node 2
…
VALIDATOR_PUBKEYS=031c…,02ab…,03ef…,02cd…   <- public, goes on EVERY node
```

Now generate the shared network secrets, also once:

```bash
openssl rand -hex 32    # NETWORK_CA_SEED  - SAME value on every node (network TLS authority)
openssl rand -hex 32    # JWT_SECRET       - can be different per node
```

Then, **on each Pi separately**, generate that node's own disk-encryption key:

```bash
openssl rand -hex 16    # STORAGE_AES_KEY  - DIFFERENT on every node
```

> **What changed (audit fixes HC-003/HC-005).** The network's TLS certificate authority is now
> derived from `NETWORK_CA_SEED`, no longer from `STORAGE_AES_KEY`. So:
> - `NETWORK_CA_SEED` is the network's master secret: identical on every node, keep it private.
>   Anyone holding it can issue node certificates.
> - `STORAGE_AES_KEY` is now a true per-node secret: use a different value on each Pi. A stolen
>   SD card no longer gives access to the other nodes or to the network authority.
> - The node refuses to start if `NETWORK_CA_SEED` is missing, shorter than 32 bytes, or equal to
>   `STORAGE_AES_KEY`.

### 7.3 Write the settings file on each Pi

```bash
nano /home/pi/hybridchain.env
```

Paste this, filling in the values. `NODE_PRIVATE_KEY` and `STORAGE_AES_KEY` differ between Pis:

```bash
# --- secrets (KEEP PRIVATE) ---
NODE_PRIVATE_KEY=PASTE_THIS_NODES_OWN_NODE_n_PRIVATE_KEY
STORAGE_AES_KEY=PASTE_THIS_NODES_OWN_16_BYTE_HEX    # different on every node
NETWORK_CA_SEED=PASTE_THE_SHARED_32_BYTE_HEX        # identical on every node
JWT_SECRET=PASTE_A_LONG_RANDOM_STRING

# --- the validator set (public, identical on every node) ---
VALIDATOR_PUBKEYS=PASTE_THE_WHOLE_COMMA_SEPARATED_LINE

# --- what kind of node this is ---
NODE_ROLE=VALIDATOR       # VALIDATOR / GATEWAY / OBSERVER
NODE_NAME=pi-node-1

# --- storage & ports ---
STORAGE_PATH=/home/pi/chain-data
SERVER_PORT=8080          # the web API (NOT "API_PORT" - that setting is ignored)
P2P_PORT=6001             # node-to-node traffic
COAP_PORT=5683            # sensor traffic (GATEWAY nodes)

# --- consensus rule: MUST be the same on every node ---
REQUIRE_QUANTUM_SIG=false
```

On the **other** Pis, also add the first Pi's address so they can find the network:

```bash
SEED_PEER=192.168.1.42:6001
```

Save and exit nano: `Ctrl+O`, `Enter`, `Ctrl+X`.

---

## 8. Step 6 — Run it

### 8.1 First run (watch it live)

```bash
cd /home/pi
set -a && source hybridchain.env && set +a
java -Xmx384m -DMIN_PEERS=3 -jar blockchain-java-1.0-SNAPSHOT-jar-with-dependencies.jar
```

> `-Xmx384m` tells Java "never use more than 384 MB of memory" — the value I verified the
> core runs in. On a Pi 4 with 4 GB you can raise it to `-Xmx1g`.
>
> `-DMIN_PEERS=3` works around bug HC-001: by default a node refuses to send *any* network
> message until it has 4 peers, but in a 4-validator network each node only ever has 3.
> Without this flag a 4-node network never produces a block. It must be written as `-D…` before `-jar`; putting `MIN_PEERS` in
> the env file has **no effect**.
>
> **Use exactly 4 or 7 validators — never 5 or 6.** The code computes its voting quorum as
> `2f+1`, which is only safe when the validator count is `3f+1` (4, 7, 10…). With 5 or 6
> validators a single dishonest node can make two honest nodes finalise *different* blocks at
> the same height (audit finding HC-002, proven by a test).

**You should see**, in this order:

```text
Node ID: hb…
Validators initialized: 4
[mTLS] CA initialized …
[PBFT] Initialized as hb… with 4 validators (f=1)
[INIT] Creating genesis block
Secure P2P Node (mTLS) started on port 6001
Tomcat started on port(s): 8080      <- the web API is up
```

> **What I verified vs. what I didn't.** I ran the real jar: the first six lines above are
> copied from my actual log. The **last line I could not observe** — the web server failed to
> start in my test environment because of a sandbox restriction on Windows (I confirmed with
> a 10-line Java program containing none of this project's code that fails identically). It
> is the standard Spring Boot message and is not expected to fail on a Raspberry Pi (Linux
> uses a different mechanism), but **I have not seen the REST API run live.** Test 1 below is
> your proof that it does.

If `Validators initialized:` shows `0`, your `VALIDATOR_PUBKEYS` line didn't load — the node
will then stop with `PBFT requires at least 4 validators`.

> **Known trap:** if the web API fails to start (for example port 8080 is taken), the node
> keeps running *without* an API instead of exiting. Always confirm with Test 1 below; don't
> trust "it's still running".

**Press `Ctrl+C` to stop it.**

### 8.2 Common problems

| What you see | What it means | Fix |
|---|---|---|
| `STORAGE_AES_KEY must be set` | The settings file wasn't loaded | Re-run the `set -a && source ...` line |
| `OutOfMemoryError` | Not enough memory | Lower to `-Xmx256m`; ensure `gpu_mem=16` (§5) |
| `Port 8080 was already in use` | Something else uses that port | Set `SERVER_PORT=8081` (not `API_PORT`, which is ignored) — and note the node keeps running without its API |
| `UnsupportedClassVersionError` | Wrong Java version | Re-check `java -version` shows 17 |
| Very slow / freezes | SD card too slow, or swapping | Use an A2-rated card; check `free -h` |

### 8.3 Get REAL performance numbers for your Pi

The Pi numbers in §1 are estimates. Replace them with measurements — copy the whole project
to the Pi and run:

```bash
mvn -o test -Dtest=EdgeReadinessProbe
```

It writes `docs/benchmarks/edge_probe_Linux_aarch64.txt`. Compare it to
`edge_probe_Windows11_amd64.txt` in the same folder to see your Pi's real slowdown factor.
**Please do this before trusting the estimates.**

---

## 9. Step 7 — Test it for real

### Test 1: Is it alive?
Open a **second** terminal, SSH into the Pi again, and run:

```bash
curl http://localhost:8080/api/v1/health
```

**You should see** something like `{"status":"ok","height":0,"peers":0}`.
- `height` = how many blocks exist
- `peers` = how many other nodes it's connected to

### Test 2: Can you reach it from another computer?
On your normal computer (same network), replacing the IP with your Pi's:

```bash
curl http://192.168.1.42:8080/api/v1/health
```

If this fails but Test 1 worked, a firewall is blocking it. Allow the ports on the Pi:

```bash
sudo ufw allow 8080/tcp && sudo ufw allow 6001/tcp && sudo ufw allow 5683/udp
```

### Test 3: Create an account (proves the write path and cryptography work)

This is the simplest thing that actually *does* something. It needs no login:

```bash
curl -X POST http://localhost:8080/api/v1/account/create
```

**You should see** JSON containing an `address` (starting `hb...`) and a `token`. That
means the Pi successfully generated a real secp256k1 key pair and issued a login token —
so the crypto stack works on your ARM chip.

### Test 4: Check the chain height

```bash
curl http://localhost:8080/api/v1/chain/height
```

**You should see** a number. On a brand-new single node this may stay at `0` until blocks
are produced — that is normal, not a failure.

### Test 5 (optional, the real IoT path): send a sensor reading over CoAP

Sensor readings don't go through the normal web API — they use **CoAP**, a lightweight
protocol designed for tiny devices. This only works if you set `NODE_ROLE=GATEWAY`.

Install a CoAP client on the Pi and send a reading:

```bash
sudo apt install -y libcoap2-bin
coap-client -m post -e '{"deviceId":"test-sensor-1","value":42.5}' \
  coap://localhost:5683/telemetry
```

**You should see** a `2.01 Created` response with a transaction id.

> If `coap-client` isn't found, your OS version may package it as `libcoap3-bin` — try that
> name instead. This test is optional; Tests 1–4 already prove the node works.

### Test 6: Does it survive a reboot?
```bash
sudo reboot
```
Wait 2 minutes, SSH back in, start the node again, and run Test 1. The `height` should be
**the same or higher than before** — never back to 0. If it resets to 0, the data directory
isn't being saved; check `STORAGE_PATH` points somewhere writable.

### Test 7: Two nodes talking (needs a second Pi or a second machine)
On the second node, add this line to its env file, pointing at the first Pi:

```bash
SEED_PEER=192.168.1.42:6001
```

Start it, then on **either** node run `curl http://localhost:8080/api/v1/health`.
**Expected today: this test FAILS — `peers` stays 0.** The nodes cannot complete their
encrypted (TLS) connection because of a bug in how the software creates its certificate
authority (audit finding HC-005, proven by a test that shows `PKIX path validation failed`).
Until that is fixed, **only single-node testing (Tests 1–6) works.** Do not buy extra Pis for a
multi-node network yet.

---

## 10. Step 8 — Make it run automatically forever

Right now the node stops when you close your terminal. To make it a permanent background
service:

```bash
sudo nano /etc/systemd/system/hybridchain.service
```

Paste this exactly:

```ini
[Unit]
Description=HybridChain IoT Node
After=network-online.target

[Service]
Type=simple
User=pi
WorkingDirectory=/home/pi
EnvironmentFile=/home/pi/hybridchain.env
ExecStart=/usr/bin/java -Xmx384m -DMIN_PEERS=3 -jar /home/pi/blockchain-java-1.0-SNAPSHOT-jar-with-dependencies.jar
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Save (`Ctrl+O`, `Enter`, `Ctrl+X`), then:

```bash
sudo systemctl daemon-reload
sudo systemctl enable hybridchain
sudo systemctl start hybridchain
```

Check it:

```bash
sudo systemctl status hybridchain     # should say "active (running)"
sudo journalctl -u hybridchain -f     # watch the live logs (Ctrl+C to stop watching)
```

It will now start automatically whenever the Pi powers on, and restart itself if it crashes.

---

## 11. Keeping it healthy

```bash
free -h                     # memory. If "available" is near zero, lower -Xmx
df -h                       # disk. The chain grows; watch the SD card filling
vcgencmd measure_temp       # temperature. Above 80°C it slows itself down — add a heatsink
du -sh /home/pi/chain-data  # how big the blockchain has grown
```

**Back up your secrets.** If you lose `NODE_PRIVATE_KEY`, that node's identity is gone
forever.

---

## 12. Honest limitations — please read

1. **This is not production-ready for high-value use.** The zero-knowledge proof code is
   hand-written and has not been reviewed by a cryptographer. Do not protect real money or
   safety-critical systems with it yet.
2. **The Pi 3 numbers in §1 are estimates**, not measurements. Run §8.3 to get real ones.
3. **Zero-knowledge features must stay off on a Pi.** ~5 s per proof on a desktop is already
   slow; on a Pi 3 it is around a minute.
4. **One Pi is not a blockchain.** A single node proves the software runs. Byzantine fault
   tolerance needs **at least 4 validators** — that is what `3f+1` means, and the code
   enforces it (`PBFT requires at least 4 validators`).
5. **SD cards wear out.** A node writes constantly. For anything long-running, boot from a
   USB SSD instead.

---

## 13. Quick reference

```bash
sudo systemctl start hybridchain      # start
sudo systemctl stop hybridchain       # stop
sudo systemctl restart hybridchain    # restart
sudo journalctl -u hybridchain -f     # live logs
curl http://localhost:8080/api/v1/health   # is it alive?
```
