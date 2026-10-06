from pathlib import Path
p=Path('nemupino-ci/app/src/main/java/com/oyasumi/recorder/MainActivity.kt')
s=p.read_text(encoding='utf-8')
old=s
# Stop any result audio immediately when app leaves foreground.
needle='override fun onPause() {'
if needle in s and 'nemupinoBackgroundPlaybackGuard' not in s:
    s=s.replace(needle, needle+'\n        // nemupinoBackgroundPlaybackGuard: result audio must never continue in background.\n        try { mediaPlayer?.pause() } catch (_: Exception) {}\n        try { playlistPlayer?.pause() } catch (_: Exception) {}',1)
elif needle not in s:
    # Activity has no onPause override: insert before onDestroy if available.
    marker='override fun onDestroy() {'
    block='override fun onPause() {\n        super.onPause()\n        // nemupinoBackgroundPlaybackGuard\n        try { mediaPlayer?.pause() } catch (_: Exception) {}\n        try { playlistPlayer?.pause() } catch (_: Exception) {}\n    }\n\n    '
    if marker in s: s=s.replace(marker,block+marker,1)
# Clarify common status strings without changing analysis semantics.
s=s.replace('波形を準備しています…','波形を読み込み中…')
s=s.replace('解析を再試行','再解析')
if s==old: raise SystemExit('UX patch found no applicable targets')
p.write_text(s,encoding='utf-8')
print('UX patch applied: background playback guard + clearer result status labels')
