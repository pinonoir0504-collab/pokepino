from pathlib import Path
import re
p=Path('/tmp/pinowatch11720/app/src/main/java/jp/pinowatch/app/MainActivity.java')
s=p.read_text()
# Remove only invocations, not the method body, so Settings can retain the developer hook.
for name in ('showWorkerUrlDialog','promptWorkerUrl','configureWorkerUrl'):
    s=re.sub(r'(?m)^\s*'+name+r'\(\);\s*$','',s)
s=s.replace('if (baseUrl == null || baseUrl.isEmpty()) { showWorkerUrlDialog(); return; }','')
s=s.replace('if (workerUrl == null || workerUrl.isEmpty()) { showWorkerUrlDialog(); return; }','')
p.write_text(s)
print('Removed developer Worker prompt invocations from startup')
