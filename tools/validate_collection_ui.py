#!/usr/bin/env python3
"""Validate the bundled WebView document without starting Android."""
import re
import subprocess
from pathlib import Path

html=(Path(__file__).resolve().parents[1]/"app/src/main/assets/collection.html").read_text(encoding="utf-8")
scripts=re.findall(r"<script[^>]*>([\\s\\S]*?)</script>",html)
if not scripts:
    raise SystemExit("No script in collection.html")
for js in scripts:
    result=subprocess.run(["node","--check"],input=js,text=True,capture_output=True)
    if result.returncode:
        raise SystemExit("Broken collection JavaScript: "+result.stderr)
for marker in ("getCatalog","getGalleries","getPickedPhotos","changeCount","searchMatch","releaseImages","galleryPanel","groupRow","setFilter","showGroup"):
    assert marker in html,marker
print("Collection JavaScript syntax and core actions: PASS")
