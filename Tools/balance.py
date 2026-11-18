#!/usr/bin/env python3
"""Export/import catalog balance CSVs by stable ID; unknown IDs and malformed numeric values fail."""
import argparse,csv,json,subprocess,sys
from pathlib import Path
root=Path(__file__).resolve().parents[1];source=root/'app/src/main/assets/content/catalog.json'
ap=argparse.ArgumentParser();ap.add_argument('command',choices=['export','import']);ap.add_argument('--directory',type=Path,default=root/'docs/balance');args=ap.parse_args()
catalog=json.loads(source.read_text());tables=('weapons','mods','enemies','artifacts','rooms')
if args.command=='export':
 args.directory.mkdir(parents=True,exist_ok=True)
 for table in tables:
  with (args.directory/(table+'.csv')).open('w',newline='',encoding='utf-8') as f:
   writer=csv.DictWriter(f,fieldnames=list(catalog[table][0]));writer.writeheader();writer.writerows(catalog[table])
else:
 for table in tables:
  path=args.directory/(table+'.csv')
  if not path.exists():continue
  by_id={row['id']:row for row in catalog[table]};seen=set()
  with path.open(newline='',encoding='utf-8-sig') as f:
   for row in csv.DictReader(f):
    if row.get('id') not in by_id or row['id'] in seen:raise ValueError(f'Unknown/duplicate {table} ID: {row.get("id")}')
    existing=by_id[row['id']];seen.add(row['id'])
    if set(row)!=set(existing):raise ValueError(f'Unexpected columns in {table}')
    for key,value in row.items():existing[key]=int(value) if isinstance(existing[key],int) else value
  if seen!=set(by_id):raise ValueError(f'Missing records in {table}')
 temp=source.with_suffix('.json.tmp');temp.write_text(json.dumps(catalog,ensure_ascii=False,indent=2)+'\n')
 subprocess.run([sys.executable,str(root/'Tools/generate_catalog.py'),'--validate',str(temp)],check=True);temp.replace(source)
 subprocess.run([sys.executable,str(root/'Tools/generate_catalog.py')],check=True)
print(f'{args.command}: {", ".join(tables)} · {args.directory}')
