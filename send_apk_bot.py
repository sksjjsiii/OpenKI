import json
import os
import subprocess
import threading
import time
import traceback

import requests

TOKEN = "9238298077:AAEoDRRLGlIpHfqVJIRvuasd6jOzl_7v_BU"
API = f"https://api.telegram.org/bot{TOKEN}"
CHATS_FILE = "/tmp/androco_bot_chats.json"
APK_PATH_FILE = "/tmp/androco_apk_path.txt"
LOG_FILES = [
    ("/tmp/termux-gen.log", "generator"),
    ("/tmp/after-termux-gen.log", "post-build"),
]

lock = threading.Lock()
chats = set()
offset = None
log_offsets = {}
last_apk_sent = None


def log(msg):
    print(time.strftime("%Y-%m-%d %H:%M:%S"), msg, flush=True)


def load_chats():
    try:
        if os.path.exists(CHATS_FILE):
            with open(CHATS_FILE) as f:
                data = json.load(f)
            return set(int(x) for x in data)
    except Exception as e:
        log(f"load_chats error: {e}")
    return set()


def save_chats():
    try:
        with open(CHATS_FILE, "w") as f:
            json.dump(sorted(chats), f)
    except Exception as e:
        log(f"save_chats error: {e}")


def send_message(chat_id, text):
    try:
        for i in range(0, len(text), 3500):
            chunk = text[i:i+3500]
            r = requests.post(f"{API}/sendMessage", data={"chat_id": chat_id, "text": chunk}, timeout=30)
            if r.status_code != 200:
                log(f"sendMessage {chat_id} status={r.status_code} body={r.text[:200]}")
    except Exception as e:
        log(f"send_message error: {e}")


def build_process_running():
    try:
        p1 = subprocess.run(["pgrep", "-af", "build-termux.sh --name com.andoco.app"], capture_output=True, text=True)
        p2 = subprocess.run(["pgrep", "-af", "after_termux_gen.sh"], capture_output=True, text=True)
        d = subprocess.run(["docker", "ps", "--format", "{{.Names}}"], capture_output=True, text=True)
        out = (p1.stdout or "") + (p2.stdout or "") + (d.stdout or "")
        return bool(out.strip()), out.strip()
    except Exception:
        return False, ""


def find_candidate_apks():
    cands = []
    roots = ["/home/runner/work/OpenKI/OpenKI/termux-generator", "/home/runner/work/OpenKI/OpenKI/androco"]
    for base in roots:
        if not os.path.isdir(base):
            continue
        for root, _, files in os.walk(base):
            for fn in files:
                if not fn.endswith('.apk'):
                    continue
                path = os.path.join(root, fn)
                # prefer generator-produced final ones
                score = 0
                if 'termux-generator' in path and 'termux-apps-main/termux-app/app/build/outputs/apk' in path:
                    score += 100
                if 'arm64-v8a' in fn:
                    score += 20
                elif 'universal' in fn:
                    score += 10
                try:
                    m = os.path.getmtime(path)
                    s = os.path.getsize(path)
                except Exception:
                    m, s = 0, 0
                cands.append((score, m, s, path))
    cands.sort(reverse=True)
    return cands


def get_configured_apk():
    try:
        if os.path.exists(APK_PATH_FILE):
            p = open(APK_PATH_FILE).read().strip()
            if p and p != "NO_APK" and os.path.exists(p):
                return p, "configured"
            if p == "NO_APK":
                return None, "NO_APK"
            if p:
                return None, f"missing:{p}"
    except Exception as e:
        return None, f"error:{e}"
    return None, "unset"


def send_apk(chat_id):
    apk, state = get_configured_apk()
    running, info = build_process_running()
    if not apk:
        msg = [f"APK نهایی هنوز آماده نیست (وضعیت: {state})."]
        if running:
            msg.append("بیلد/ساخت بوت‌استرپ در حال اجراست؛ پس از اتمام APK کامل ارسال خواهد شد.")
        else:
            msg.append("فرآیند بیلد فعال دیده نمی‌شود؛ در حال بررسی/راه‌اندازی مجدد ممکن است لازم باشد.")
        cands = find_candidate_apks()
        if cands:
            msg.append("APKهای موجود (ممکن است ناقص/قدیمی باشند):")
            for _, _, s, p in cands[:5]:
                msg.append(f"- {os.path.basename(p)} ({s//1024//1024}MB)\n  {p}")
            msg.append("برای ارسال یکی از این‌ها (با مسئولیت خودتان، ممکن است ناقص باشد) از /apk force استفاده کنید.")
        send_message(chat_id, "\n".join(msg))
        return False
    try:
        size_mb = os.path.getsize(apk) // 1024 // 1024
        send_message(chat_id, f"در حال ارسال APK...\n{os.path.basename(apk)} ({size_mb}MB)\n{apk}")
        with open(apk, "rb") as f:
            resp = requests.post(
                f"{API}/sendDocument",
                data={"chat_id": chat_id, "caption": "Andoco (com.andoco.app) APK"},
                files={"document": (os.path.basename(apk), f, "application/vnd.android.package-archive")},
                timeout=1200,
            )
        log(f"sendDocument chat={chat_id} status={resp.status_code} resp={resp.text[:200]}")
        if resp.status_code != 200:
            send_message(chat_id, f"ارسال ناموفق: {resp.status_code}\n{resp.text[:500]}")
            return False
        return True
    except Exception as e:
        log(f"send_apk error: {e}\n{traceback.format_exc()}")
        send_message(chat_id, f"خطا در ارسال APK: {e}")
        return False


def send_apk_force(chat_id):
    cands = find_candidate_apks()
    if not cands:
        send_message(chat_id, "هیچ فایل APK پیدا نشد.")
        return False
    # prefer newest high-score
    apk = cands[0][3]
    try:
        size_mb = os.path.getsize(apk) // 1024 // 1024
        send_message(chat_id, f"ارسال APK موجود (ممکن است ناقص/قدیمی باشد):\n{os.path.basename(apk)} ({size_mb}MB)\n{apk}")
        with open(apk, "rb") as f:
            resp = requests.post(
                f"{API}/sendDocument",
                data={"chat_id": chat_id, "caption": "APK (force) - may be incomplete"},
                files={"document": (os.path.basename(apk), f, "application/vnd.android.package-archive")},
                timeout=1200,
            )
        log(f"sendDocument(force) chat={chat_id} status={resp.status_code} resp={resp.text[:200]}")
        if resp.status_code != 200:
            send_message(chat_id, f"ارسال ناموفق: {resp.status_code}\n{resp.text[:500]}")
            return False
        return True
    except Exception as e:
        log(f"send_apk_force error: {e}")
        send_message(chat_id, f"خطا در ارسال: {e}")
        return False


def send_progress_snapshot(chat_id=None):
    parts = []
    for path, label in LOG_FILES:
        try:
            if os.path.exists(path):
                with open(path, "r", errors="ignore") as f:
                    data = f.read()
                if data.strip():
                    lines = [ln for ln in data.splitlines() if ln.strip()]
                    parts.append(f"[{label} tail]\n" + "\n".join(lines[-25:]))
            else:
                parts.append(f"[{label}] لاگ موجود نیست: {path}")
        except Exception as e:
            parts.append(f"[{label}] خطا: {e}")
    apk, state = get_configured_apk()
    running, info = build_process_running()
    parts.append(f"[build] running={running}\n{info[-800:] if info else ''}")
    parts.append(f"[apk] configured={apk} state={state} exists={bool(apk and os.path.exists(apk))}")
    text = "\n\n".join(parts) if parts else "هنوز لاگی موجود نیست."
    targets = [chat_id] if chat_id is not None else list(chats)
    for cid in targets:
        send_message(cid, text[-3500:])


def handle_update(upd):
    msg = upd.get("message") or upd.get("edited_message")
    if not msg:
        return
    chat = msg.get("chat", {})
    chat_id = chat.get("id")
    text = (msg.get("text") or "").strip()
    if chat_id is None:
        return
    with lock:
        is_new = chat_id not in chats
        chats.add(chat_id)
        save_chats()
    log(f"update from chat={chat_id} new={is_new} text={text!r}")
    if text.startswith("/start"):
        send_message(chat_id, "ربات فعال است. پیشرفت بیلد هر ~۲۰ ثانیه ارسال می‌شود.\nدستورات: /status /apk /apk force /help")
        send_progress_snapshot(chat_id)
    elif text.startswith("/status"):
        send_progress_snapshot(chat_id)
    elif text.startswith("/apk"):
        if "force" in text.split():
            send_apk_force(chat_id)
        else:
            send_apk(chat_id)
    elif text.startswith("/help"):
        send_message(chat_id, "/start - شروع\n/status - وضعیت بیلد و لاگ‌ها\n/apk - ارسال APK نهایی (وقتی آماده شد)\n/apk force - ارسال آخرین APK موجود (ممکن است ناقص باشد)")
    else:
        send_message(chat_id, "دستور نامشخص. /status یا /apk")


def updates_loop():
    global offset
    while True:
        try:
            params = {"timeout": 30}
            if offset is not None:
                params["offset"] = offset
            r = requests.get(f"{API}/getUpdates", params=params, timeout=40)
            data = r.json()
            if not data.get("ok"):
                log(f"getUpdates not ok: {data}")
                time.sleep(3)
                continue
            for upd in data.get("result", []):
                offset = upd["update_id"] + 1
                handle_update(upd)
        except Exception as e:
            log(f"updates_loop error: {e}")
            time.sleep(3)


def tail_new(path, label):
    try:
        if not os.path.exists(path):
            return ""
        size = os.path.getsize(path)
        off = log_offsets.get(path, 0)
        if size < off:
            off = 0
        if size == off:
            return ""
        with open(path, "r", errors="ignore") as f:
            f.seek(off)
            chunk = f.read()
        log_offsets[path] = size
        chunk = chunk.strip()
        if not chunk:
            return ""
        lines = [ln for ln in chunk.splitlines() if ln.strip()]
        if not lines:
            return ""
        tail = "\n".join(lines[-40:])
        return f"[{label}]\n{tail}"
    except Exception as e:
        return f"[{label}] read error: {e}"


def progress_loop():
    for path, _ in LOG_FILES:
        try:
            if os.path.exists(path):
                log_offsets[path] = max(0, os.path.getsize(path) - 1500)
            else:
                log_offsets[path] = 0
        except Exception:
            log_offsets[path] = 0
    while True:
        try:
            chunks = []
            for path, label in LOG_FILES:
                c = tail_new(path, label)
                if c:
                    chunks.append(c)
            if chunks:
                text = "\n\n".join(chunks)
                if len(text) > 3500:
                    text = text[-3500:]
                with lock:
                    targets = list(chats)
                for cid in targets:
                    send_message(cid, text)
        except Exception as e:
            log(f"progress_loop error: {e}")
        time.sleep(20)


def apk_watch_loop():
    global last_apk_sent
    while True:
        try:
            apk, state = get_configured_apk()
            if apk and os.path.exists(apk):
                mtime = os.path.getmtime(apk)
                key = (apk, mtime, os.path.getsize(apk))
                if key != last_apk_sent:
                    with lock:
                        targets = list(chats)
                    for cid in targets:
                        send_message(cid, f"APK نهایی آماده شد:\n{os.path.basename(apk)}\nبرای دریافت /apk را بفرستید.")
                    last_apk_sent = key
        except Exception as e:
            log(f"apk_watch_loop error: {e}")
        time.sleep(30)


def main():
    global chats
    chats = load_chats()
    log(f"Bot started. chats={sorted(chats)} apk={get_configured_apk()}")
    try:
        me = requests.get(f"{API}/getMe", timeout=20).json()
        log(f"getMe: {me}")
    except Exception as e:
        log(f"getMe error: {e}")
    threading.Thread(target=updates_loop, daemon=True).start()
    threading.Thread(target=progress_loop, daemon=True).start()
    threading.Thread(target=apk_watch_loop, daemon=True).start()
    while True:
        time.sleep(60)


if __name__ == "__main__":
    main()
