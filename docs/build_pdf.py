"""manual.md → manual.pdf

manual.md는 md-to-pdf(Node) frontmatter 형식이지만 개발 환경에 Node가 없어, 같은 frontmatter의
stylesheet·margin을 그대로 적용한 HTML을 만든 뒤 Chrome headless로 PDF를 뽑는다.

사용법 (저장소 루트에서):
    python3 docs/build_pdf.py

필요: Python 패키지 markdown, pyyaml / Google Chrome
"""
import pathlib
import subprocess
import sys
import tempfile

import markdown
import yaml

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
DOCS = pathlib.Path(__file__).resolve().parent


def main() -> int:
    text = (DOCS / "manual.md").read_text(encoding="utf-8")
    _, frontmatter, body = text.split("---\n", 2)
    meta = yaml.safe_load(frontmatter)
    margin = meta["pdf_options"]["margin"]
    html_body = markdown.markdown(body, extensions=["tables", "md_in_html"])
    html = (
        '<!doctype html><html lang="ko"><head><meta charset="utf-8">'
        f"<style>@page {{ size: A4; margin: {margin}; }}</style>"
        f"<style>{meta['stylesheet']}</style></head><body>{html_body}</body></html>"
    )
    with tempfile.TemporaryDirectory() as tmp:
        html_path = pathlib.Path(tmp) / "manual.html"
        html_path.write_text(html, encoding="utf-8")
        # --virtual-time-budget: 웹폰트(Noto Sans KR) 로딩이 끝난 뒤 인쇄되도록 기다린다.
        result = subprocess.run(
            [
                CHROME, "--headless=new", "--disable-gpu", "--no-pdf-header-footer",
                "--virtual-time-budget=8000",
                f"--print-to-pdf={DOCS / 'manual.pdf'}", html_path.as_uri(),
            ],
            capture_output=True, text=True,
        )
    if result.returncode != 0:
        sys.stderr.write(result.stderr)
        return result.returncode
    print(f"wrote {DOCS / 'manual.pdf'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
