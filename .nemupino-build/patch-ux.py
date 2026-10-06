from pathlib import Path
p=Path('nemupino-ci/app/src/main/java/com/oyasumi/recorder/MainActivity.kt')
s=p.read_text(encoding='utf-8')
old=s
# Playback must stop (not merely pause) whenever app is no longer foreground.
needle='override fun onPause() {'
guard='''\n        // nemupinoBackgroundPlaybackGuard\n        try {\n            mediaPlayer?.stop()\n            mediaPlayer?.release()\n            mediaPlayer = null\n        } catch (_: Exception) { mediaPlayer = null }\n        try {\n            playlistPlayer?.stop()\n            playlistPlayer?.release()\n            playlistPlayer = null\n        } catch (_: Exception) { playlistPlayer = null }'''
if needle in s:
    # Upgrade old pause guard if present.
    oldguard='''\n        // nemupinoBackgroundPlaybackGuard\n        try { mediaPlayer?.pause() } catch (_: Exception) {}\n        try { playlistPlayer?.pause() } catch (_: Exception) {}'''
    if oldguard in s:
        s=s.replace(oldguard,guard,1)
    elif 'nemupinoBackgroundPlaybackGuard' not in s:
        s=s.replace(needle, needle+guard,1)
else:
    marker='override fun onDestroy() {'
    block='''override fun onPause() {\n        super.onPause()'''+guard+'''\n    }\n\n    '''
    if marker in s: s=s.replace(marker,block+marker,1)
# Clearer result state labels.
for a,b in {
 '波形を準備しています…':'波形を読み込み中…',
 '解析を再試行':'再解析',
 '確認待ち':'一部未判定',
 '区間再生OFF':'連続再生',
 '全ての音':'すべて',
}.items(): s=s.replace(a,b)
s=s.replace('waveformView.visibility = View.INVISIBLE','waveformView.visibility = View.VISIBLE')
s=s.replace('waveformView.visibility = View.GONE','waveformView.visibility = View.VISIBLE')
s=s.replace('height = dp(64)', 'height = dp(52)')
s=s.replace('height = dp(56)', 'height = dp(48)')
s=s.replace('TOP5', 'いびきTOP5')
if s==old: raise SystemExit('UX patch found no applicable targets')
p.write_text(s,encoding='utf-8')
print('UX patch applied: background STOP/release, clearer states, visible waveform, compact controls, clearer TOP5')
