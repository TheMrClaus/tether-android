#!/usr/bin/env python3
"""T7.3 web reference for states the S0.4 seeder never reaches (the `!` command mode, a foreground
command's Background / Stop keys, the slash palette, the `@` Agents picker, the delegate chip, the
transcript's command panel).

A RECONSTRUCTION, not a capture of the running console: each state is the web's own markup for that
state (tether components/chat-view.tsx at PARITY_BASE, copied element for element with its class
names and copy), styled by the web's own stylesheets at PARITY_BASE (app/globals.css + app/studio.css,
read with `git show`, nothing built or served), with the lucide icons and provider marks the web
draws, and the app's bundled Manrope / JetBrains Mono files standing in for next/font. It is rendered
by a headless Chromium at the S0.4 phone viewport (412 CSS px, DPR 2.625). Nothing of tether is run.

  tools/compare-screens/commands-web-reference.py [out-dir]

Environment: TETHER_REPO (default ~/git/tether), PARITY_BASE (default 7d65611),
CHROME_HEADLESS (default: the newest ~/.cache/ms-playwright/chromium_headless_shell-*).
"""
import glob
import html
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TETHER = os.environ.get("TETHER_REPO", os.path.expanduser("~/git/tether"))
BASE = os.environ.get("PARITY_BASE", "7d65611")
OUT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/tether-commands-web"
SKINS = ["tactile", "night", "precision", "machine", "studio", "studio-dark"]


def show(path):
    return subprocess.run(["git", "-C", TETHER, "show", f"{BASE}:{path}"], check=True, capture_output=True, text=True).stdout


def chrome():
    exe = os.environ.get("CHROME_HEADLESS")
    if exe:
        return exe
    found = sorted(glob.glob(os.path.expanduser("~/.cache/ms-playwright/chromium_headless_shell-*/chrome-headless-shell-linux64/chrome-headless-shell")))
    if not found:
        sys.exit("no headless Chromium: set CHROME_HEADLESS")
    return found[-1]


def lucide(name, size):
    """The lucide-react icon node (dist/esm/icons/<name>.mjs) as an inline <svg>."""
    src = open(os.path.join(TETHER, "node_modules/lucide-react/dist/esm/icons", f"{name}.mjs")).read()
    start = src.index("node: [")
    node = src[start : src.index("};", start)]
    parts = []
    for tag, attrs in re.findall(r'\[\s*"(\w+)",\s*\{([^}]*)\}\s*\]', node):
        kv = [(k, v) for k, v in re.findall(r'(\w+):\s*"([^"]*)"', attrs) if k != "key"]
        parts.append(f"<{tag} " + " ".join(f'{re.sub("([A-Z])", lambda m: "-" + m.group(1).lower(), k)}="{v}"' for k, v in kv) + "/>")
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
            f'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + "".join(parts) + "</svg>")


LOGO_SRC = None


def logo(provider):
    """components/provider-logo.tsx LOGO_MARKS[provider] as the web draws it (fill currentColor, 1em)."""
    global LOGO_SRC
    if LOGO_SRC is None:
        LOGO_SRC = show("components/provider-logo.tsx")
    m = re.search(provider + r':\s*\{\s*viewBox:\s*"([^"]+)",\s*path:\s*"([^"]+)"', LOGO_SRC)
    if not m:
        return f'<span class="provider-logo-letter">{provider[:1].upper()}</span>'
    return f'<svg class="provider-logo" viewBox="{m.group(1)}" width="1em" height="1em" fill="currentColor" aria-hidden="true"><path d="{m.group(2)}"/></svg>'


def toolbar(actions):
    return f"""
      <div class="chat-composer-toolbar">
        <div class="settings-sheet-trigger-row" aria-label="Session options">
          <button type="button" class="settings-sheet-trigger settings-sheet-trigger-combined">
            <span class="provider-glyph provider-claude settings-sheet-trigger-glyph" aria-hidden="true">{logo("claude")}</span>
            <span class="settings-sheet-trigger-label">Opus (1M context)</span>{lucide("sliders-horizontal", 14)}
          </button>
        </div>
        <div class="chat-composer-footer">
          <button type="button" class="chat-attach-btn" aria-label="Add attachment">{lucide("paperclip", 16)}</button>
          <div class="chat-composer-end"><div class="chat-composer-actions">{actions}</div></div>
        </div>
      </div>"""


SEND = f'<button class="chat-send" disabled>{lucide("send", 18)} <span>Send</span></button>'
SEND_ON = f'<button class="chat-send">{lucide("send", 18)} <span>Send</span></button>'


def composer(shell_class, before_well, draft, actions, placeholder="Message the agent…", command=False):
    textarea = (f'<textarea class="chat-input{" chat-input--command" if command else ""}" rows="1" '
                f'placeholder="{html.escape(placeholder)}" aria-label="Message the agent">{html.escape(draft)}</textarea>')
    return f"""
  <div class="chat-composer">
    <div class="chat-composer-shell{shell_class}">
      {before_well}
      <div class="chat-composer-well">
        {textarea}
        {toolbar(actions)}
      </div>
    </div>
  </div>"""


def slash_menu():
    rows = [
        ("compact", "&lt;instructions&gt;", "Clear history but keep a summary", True),
        ("context", None, "Show context usage", True),
        ("model", "[model]", "Switch the model for this session", True),
        ("exit", None, "Exit the REPL", False),
    ]
    items = []
    for i, (name, arg, desc, ok) in enumerate(rows):
        tag = '<span class="chat-slash-tag">Tether</span>' if ok else f'<span class="chat-slash-tag is-terminal">{lucide("terminal", 11)} terminal only</span>'
        arg_html = f'<span class="chat-slash-arg"> {arg}</span>' if arg else ""
        items.append(
            f'<button type="button" role="option" class="chat-slash-item{" is-active" if i == 0 else ""}{"" if ok else " is-unsupported"}">'
            f'<span class="chat-slash-name">/{name}{arg_html}</span><span class="chat-slash-desc">{desc}</span>{tag}</button>'
        )
    return '<div class="chat-slash-menu" role="listbox" aria-label="Slash commands">' + "".join(items) + "</div>"


def mention_menu(rows):
    items = ['<div class="chat-mention-section-label" role="presentation">Agents</div>']
    for i, (provider, name, meta, model) in enumerate(rows):
        meta_html = f'<span class="chat-mention-model">{meta}</span>' if model else f'<span class="chat-mention-status">{meta}</span>'
        items.append(
            f'<button type="button" role="option" class="chat-mention-item chat-mention-agent{" is-active" if i == 0 else ""}">'
            f'<span class="chat-mention-logo" aria-hidden="true">{logo(provider)}</span>'
            f'<span class="chat-mention-body"><span class="chat-mention-name">{name}</span><span class="chat-mention-meta">{meta_html}</span></span>'
            f'<span class="chat-mention-tag">delegate</span></button>'
        )
    return '<div class="chat-mention-menu" role="listbox" aria-label="Mention a session or an agent">' + "".join(items) + "</div>"


def select(label, extra=""):
    return f'<div class="tether-select"><button type="button" class="tether-select-trigger chat-mode-select{extra}"><span>{label}</span>{lucide("chevron-down", 13)}</button></div>'


def delegate_bar():
    return f"""<div class="chat-delegate-bar" role="group" aria-label="Delegating to an agent">
      <span class="chat-delegate-chip">
        <span class="chat-mention-logo" aria-hidden="true">{logo("claude")}</span>
        <span class="chat-delegate-chip-name">@Claude</span>
        <span class="chat-delegate-chip-model">Opus 5</span>
        <span class="chat-delegate-chip-mode">review</span>
        <button type="button" class="chat-delegate-chip-remove" aria-label="Remove the delegate mention">{lucide("x", 14)}</button>
      </span>
      {select("Opus 5", " chat-model-select")}{select("Default")}{select("review")}
    </div>"""


def panel(command, status, running, failed, segments, foot):
    body = "".join(f'<span{" class=\"chat-command-stderr\"" if err else ""}>{html.escape(t)}</span>' for err, t in segments)
    spin = lucide("loader", 12).replace("<svg ", '<svg class="chat-spin" ') if running else ""
    cls = (" is-failed" if failed else "") + (" is-running" if running else "")
    return f"""<div class="chat-row chat-row-command"><div class="chat-command-panel{cls}">
      <div class="chat-command-panel-head">{lucide("terminal", 13)}<code class="chat-command-panel-cmd">{html.escape(command)}</code>
        <span class="chat-command-panel-status{" is-failed" if failed else ""}">{spin} {status}</span></div>
      <pre class="chat-command-panel-body">{body}{'<span class="chat-caret" aria-hidden="true"></span>' if running else ""}</pre>
      <div class="chat-command-panel-foot">{foot}</div></div></div>"""


COMMAND = "npm test -- --runInBand"
LOG = "/w/.tether/commands/c-1.log"
AGENTS = [("claude", "Claude", "Opus 5", True), ("codex", "Codex", "loading models…", False), ("opencode", "OpenCode", "2 models", False)]

STATES = {
    "command": composer(
        " chat-composer-shell--command",
        f'<div class="chat-command-flag" role="status">{lucide("terminal", 13)}<span>Command mode — runs in the session directory; the agent watches and comments on the output.</span></div>',
        "!" + COMMAND,
        f'<button class="chat-send chat-send--command" aria-label="Run command and send output to the agent">{lucide("terminal", 18)} <span>Send to agent</span></button>'
        f'<button class="chat-send chat-send--command-bg" aria-label="Run command in the background">{lucide("send-to-back", 18)} <span>Background</span></button>',
        command=True,
    ),
    "foreground": composer(
        "", "", "",
        f'<button class="chat-send chat-send--command-bg" aria-label="Send this command to the background">{lucide("send-to-back", 18)} <span>Background</span></button>'
        f'<button class="chat-send chat-interrupt" aria-label="Stop the command">{lucide("circle-stop", 18)} <span>Stop</span></button>',
        placeholder="Agent is working — message queues",
    ),
    "slash": composer("", slash_menu(), "/", SEND_ON),
    "mention": composer("", mention_menu(AGENTS), "Ask @", SEND_ON),
    "delegate": composer("", delegate_bar(), "Ask", SEND_ON),
    "panel": '<div style="background: var(--mineral-deep); padding: 12px 12px 12px;">'
    + panel(COMMAND, "running…", True, False,
            [(False, "> app@1.0.0 test\n> jest --runInBand\n\nPASS src/format.test.ts\n"), (True, "console.warn: slow test (1.8 s)\n"), (False, "RUNS src/sync.test.ts\n")],
            f"Full output: <code>{LOG}</code>")
    + panel(COMMAND, "exit 1", False, True,
            [(False, "PASS src/format.test.ts\n"), (True, "FAIL src/sync.test.ts\n  ● outbox › drains in order\n")],
            f"Output truncated above — full log: <code>{LOG}</code>")
    + "</div>",
}


def page(skin, body, css_dir, fonts):
    return f"""<!doctype html><html data-theme="{skin}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
@font-face {{ font-family: "Manrope Variable"; src: url("file://{fonts}/manrope_variable.ttf"); font-weight: 200 800; }}
@font-face {{ font-family: "JetBrains Mono Variable"; src: url("file://{fonts}/jetbrains_mono_variable.ttf"); font-weight: 100 800; }}
</style>
<link rel="stylesheet" href="file://{css_dir}/globals.css"><link rel="stylesheet" href="file://{css_dir}/studio.css">
<style>
/* The harness: the deck pinned to the window's foot like the shell's composer (room above for the menus). */
html, body {{ height: 100%; margin: 0; }}
body {{ display: flex; flex-direction: column; justify-content: flex-end; }}
[data-theme^="studio"] body > div[style] {{ background: var(--graphite) !important; }}
</style></head><body>{body}</body></html>"""


def main():
    os.makedirs(OUT, exist_ok=True)
    css_dir = os.path.join(OUT, "css")
    os.makedirs(css_dir, exist_ok=True)
    for name in ("globals.css", "studio.css"):
        open(os.path.join(css_dir, name), "w").write(show(f"app/{name}"))
    fonts = os.path.join(ROOT, "core/designsystem/src/main/res/font")
    exe = chrome()
    for state, body in STATES.items():
        for skin in SKINS:
            d = os.path.join(OUT, state)
            os.makedirs(d, exist_ok=True)
            src = os.path.join(d, f"{skin}-phone.html")
            open(src, "w").write(page(skin, body, css_dir, fonts))
            png = os.path.join(d, f"{skin}-phone.png")
            subprocess.run([exe, "--headless", "--no-sandbox", "--hide-scrollbars", "--disable-gpu", "--force-device-scale-factor=2.625",
                            "--window-size=412,915", f"--screenshot={png}", "file://" + src], check=True, capture_output=True)
            print(png)


if __name__ == "__main__":
    main()
