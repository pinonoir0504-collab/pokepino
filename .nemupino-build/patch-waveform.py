from pathlib import Path
p = Path('nemupino-ci/app/src/main/java/com/oyasumi/recorder/MainActivity.kt')
s = p.read_text(encoding='utf-8')
old = s
# Detailed waveform only: reduce peak height and visual weight further.
s = s.replace('height * .40f', 'height * .16f')
s = s.replace('height * 0.40f', 'height * 0.16f')
s = s.replace('height * .24f', 'height * .16f')
s = s.replace('height * 0.24f', 'height * 0.16f')
s = s.replace('alpha = .25f', 'alpha = .08f')
s = s.replace('alpha = 0.25f', 'alpha = 0.08f')
s = s.replace('alpha = .12f', 'alpha = .08f')
s = s.replace('alpha = 0.12f', 'alpha = 0.08f')
s = s.replace('alpha = .95f', 'alpha = .72f')
s = s.replace('alpha = 0.95f', 'alpha = 0.72f')
s = s.replace('alpha = .86f', 'alpha = .72f')
s = s.replace('alpha = 0.86f', 'alpha = 0.72f')
if s == old:
    raise SystemExit('waveform constants not found; refusing silent no-op')
p.write_text(s, encoding='utf-8')
print('Detailed waveform patched: height<=16%, raw alpha<=0.08, main alpha<=0.72')
