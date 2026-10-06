from pathlib import Path
p = Path('nemupino-ci/app/src/main/java/com/oyasumi/recorder/MainActivity.kt')
s = p.read_text(encoding='utf-8')
old = s
# Detailed waveform only: SnoreLab-like narrow waveform around the center line.
for a,b in {
    'height * .40f':'height * .10f', 'height * 0.40f':'height * 0.10f',
    'height * .24f':'height * .10f', 'height * 0.24f':'height * 0.10f',
    'height * .16f':'height * .10f', 'height * 0.16f':'height * 0.10f',
    'alpha = .25f':'alpha = .05f', 'alpha = 0.25f':'alpha = 0.05f',
    'alpha = .12f':'alpha = .05f', 'alpha = 0.12f':'alpha = 0.05f',
    'alpha = .08f':'alpha = .05f', 'alpha = 0.08f':'alpha = 0.05f',
    'alpha = .95f':'alpha = .66f', 'alpha = 0.95f':'alpha = 0.66f',
    'alpha = .86f':'alpha = .66f', 'alpha = 0.86f':'alpha = 0.66f',
    'alpha = .72f':'alpha = .66f', 'alpha = 0.72f':'alpha = 0.66f',
}.items():
    s = s.replace(a,b)
if s == old:
    raise SystemExit('waveform constants not found; refusing silent no-op')
p.write_text(s, encoding='utf-8')
print('Detailed waveform patched: height<=10%, raw alpha<=0.05, main alpha<=0.66')
