"""Assembles the static concept pages from src/ into overview.html and decision-detail.html (no dependencies)."""
from pathlib import Path

ROOT = Path(__file__).parent
SRC = ROOT / "src"
FONTS = (
    '<link rel="preconnect" href="https://fonts.googleapis.com">'
    '<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>'
    '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700'
    '&family=JetBrains+Mono:wght@400;500&display=swap">'
)
OP_ON = """<div class="op-state"%s>
      <div class="op-state-row"><span class="dot"></span>Monitoring on</div>
      <p>Ad changes need your approval (L1).</p>
    </div>"""
OP_STOPPED = """<div class="op-state" data-when="blocked" hidden>
      <div class="op-state-row" style="color:var(--danger)"><span class="dot danger"></span>Ad changes stopped</div>
      <p>Monitoring continues. Nothing is sent to ad accounts until changes are resumed.</p>
    </div>"""
BANNER = """<div class="safety-banner" role="status" data-when="blocked" hidden>
      <svg class="icon"><use href="#i-stop"/></svg>Ad changes are stopped for this store.
      <span>Reason: “Warehouse stock count in progress” · Maja Nowak, today 10:40</span>
      <a href="#">Open settings</a>
    </div>"""

PAGES = {
    "overview.html": {
        "title": "Operator Overview Concept",
        "body": "overview.html",
        "cur": "overview",
        "crumbs": '<span aria-current="page">Overview</span>',
        "op": OP_ON % "",
        "banner": "",
    },
    "decision-detail.html": {
        "title": "Decision Detail Concept",
        "body": "decision.html",
        "cur": "decisions",
        "crumbs": '<a href="#">Decisions</a><svg class="icon icon-sm"><use href="#i-chev"/></svg>'
                  '<span aria-current="page">Hydrating Serum 30 ml</span>',
        "op": OP_ON % ' data-when="waiting check-failed executed"' + "\n    " + OP_STOPPED,
        "banner": BANNER,
    },
}


def build() -> None:
    css = (SRC / "shared.css").read_text()
    icons = (SRC / "icons.svg").read_text()
    shell = (SRC / "shell.html").read_text()
    for name, page in PAGES.items():
        html = shell
        for section in ("overview", "decisions"):
            html = html.replace(f"%%CUR_{section}%%", 'aria-current="page"' if section == page["cur"] else "")
        html = (html.replace("%%CRUMBS%%", page["crumbs"]).replace("%%OPSTATE%%", page["op"])
                .replace("%%BANNER%%", page["banner"]).replace("%%CONTENT%%", (SRC / page["body"]).read_text()))
        doc = (
            '<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n'
            '<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">\n'
            f'<title>{page["title"]}</title>\n<meta name="claude:viewport-fit-sides" content="cover">\n'
            f"{FONTS}\n<style>\n{css}</style>\n</head>\n<body>\n{icons}\n{html}</body>\n</html>\n"
        )
        (ROOT / name).write_text(doc)


if __name__ == "__main__":
    build()
