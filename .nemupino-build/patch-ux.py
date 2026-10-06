from pathlib import Path
p=Path('nemupino-ci/app/src/main/java/com/oyasumi/recorder/MainActivity.kt')
s=p.read_text(encoding='utf-8')
old=s
# Playback must stop/pause whenever app is no longer foreground.
needle='override fun onPause() {'
if needle in s and 'nemupinoBackgroundPlaybackGuard' not in s:
    s=s.replace(needle, needle+'\n        // nemupinoBackgroundPlaybackGuard\n        try { mediaPlayer?.pause() } catch (_: Exception) {}\n        try { playlistPlayer?.pause() } catch (_: Exception) {}',1)
elif needle not in s:
    marker='override fun onDestroy() {'
    block='override fun onPause() {\n        super.onPause()\n        // nemupinoBackgroundPlaybackGuard\n        try { mediaPlayer?.pause() } catch (_: Exception) {}\n        try { playlistPlayer?.pause() } catch (_: Exception) {}\n    }\n\n    '
    if marker in s: s=s.replace(marker,block+marker,1)
# Clearer result state labels.
for a,b in {
 '波形を準備しています…':'波形を読み込み中…',
 '解析を再試行':'再解析',
 '確認待ち':'一部未判定',
 '区間再生OFF':'連続再生',
 '全ての音':'すべて',
}.items(): s=s.replace(a,b)
# Prefer immediate coarse waveform: do not hide an already available waveform behind loading copy.
s=s.replace('waveformView.visibility = View.INVISIBLE','waveformView.visibility = View.VISIBLE')
s=s.replace('waveformView.visibility = View.GONE','waveformView.visibility = View.VISIBLE')
# Compact result controls where XML/programmatic heights use common 56/64dp values.
s=s.replace('height = dp(64)', 'height = dp(52)')
s=s.replace('height = dp(56)', 'height = dp(48)')
# More useful TOP ranking wording; scoring implementation remains unchanged unless already weighted.
s=s.replace('TOP5', 'いびきTOP5')
if s==old: raise SystemExit('UX patch found no applicable targets')
p.write_text(s,encoding='utf-8')
print('UX patch applied: background pause, clearer states, visible waveform, compact controls, clearer TOP5')
