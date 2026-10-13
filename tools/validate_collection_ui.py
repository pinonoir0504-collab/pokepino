#!/usr/bin/env python3
"""Validate the bundled WebView document without starting Android."""
import re
import subprocess
from pathlib import Path

html=(Path(__file__).resolve().parents[1]/"app/src/main/assets/collection.html").read_text(encoding="utf-8")
scripts=re.findall(r"(?s)<script[^>]*>(.*?)</script>",html)
if not scripts:
    raise SystemExit("No script in collection.html")
for js in scripts:
    result=subprocess.run(["node","--check"],input=js,text=True,capture_output=True)
    if result.returncode:
        raise SystemExit("Broken collection JavaScript: "+result.stderr)
for marker in ("getCatalog","getOwned","changeCount","searchMatch","searchInput","groupRow","setFilter","showGroup"):
    assert marker in html,marker
for excluded in ('nav-scan', 'openScanner()', 'setGalleryPicture(', 'chooseGallery(', 'galleryPanel('):
    assert excluded not in html, "Obsolete photo workflow remains: "+excluded
print("Manual collection UI syntax and core actions: PASS")
