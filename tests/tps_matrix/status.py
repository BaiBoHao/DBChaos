#!/usr/bin/env python3
"""Read-only progress summary for a long-running TPS experiment."""
import json,sys,csv
from pathlib import Path
root=Path(sys.argv[1]);cal=root/'calibration.json';runs=root/'runs.json'
if cal.exists():
 data=json.loads(cal.read_text())
 for p,c in data.items():
  print(p, 'calibration',len(c['candidates']),'/4','selected='+str(c.get('selected_terminals','pending')))
if runs.exists():
 data=json.loads(runs.read_text());fault=[r for r in data if r['mode']!='control']
 print('Fault rounds recorded:',len(fault),'/27; controls:',len(data)-len(fault),'/3')
 if data:
  r=data[-1];print('Last:',r['label'],r['status'])
  m=r.get('resilience',{})
  corrected=root/r['label']/'derived-resilience.json'
  if corrected.exists():m=json.loads(corrected.read_text());print('Pr source: latest covered recovery windows; raw runtime JSON retained')
  if m:print('P0/Pd/Pr:',m.get('P0'),m.get('Pd'),m.get('Pr'),'max/mean drop:',m.get('max_drop_pct'),m.get('mean_drop_pct'),'recovered:',m.get('recovered'))
logs=list(root.glob('*/**/business.log'))
if logs:
 p=max(logs,key=lambda p:p.stat().st_mtime)
 with p.open('rb') as f:
  f.seek(max(0,p.stat().st_size-8000));lines=f.read().decode(errors='replace').splitlines()
 progress=[s for s in lines if s.startswith('progress:')]
 print('Latest business:',str(p.relative_to(root)))
 if progress:print(progress[-1])

 injection=p.parent/'injector.log'
 if injection.exists():
  recent=[line for line in injection.read_text().splitlines() if line.startswith(('[Monitor]','[Resilience]','[ConnectionAnalysis]'))]
  if recent:print('Monitor:',recent[-1])

 series=p.parent/'report/throughput.csv'
 if series.exists():
  rows=list(csv.DictReader(series.open()))
  full=[r for r in rows if r['complete']=='true']
  if full:
   r=full[-1];print('Latest full TPS window:',r['phase'],r['successful_tps'],'TPS; errors='+r['errors']+'; source_covered='+r['source_covered'])

verification=root/'verification.json'
if verification.exists():
 checks=json.loads(verification.read_text())
 print('Raw-window/formula verification:',sum(bool(x.get('all_checks_pass')) for x in checks),'/',len(checks))
