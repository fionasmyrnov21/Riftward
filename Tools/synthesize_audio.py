#!/usr/bin/env python3
"""Original deterministic ambient score and UI/mechanical sound effects. No downloaded samples."""
from array import array
import math,random,wave
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]/'app/src/main/assets/audio';ROOT.mkdir(parents=True,exist_ok=True)
RATE=22050;TAU=2*math.pi
def write(name,samples):
 peak=max(abs(s) for s in samples) or 1;scale=.78/max(1,peak)
 pcm=array('h',(int(max(-1,min(1,s*scale))*32767) for s in samples))
 with wave.open(str(ROOT/(name+'.wav')),'wb') as f:f.setnchannels(1);f.setsampwidth(2);f.setframerate(RATE);f.writeframes(pcm.tobytes())
def music(name,mission=False):
 duration=48;notes=[(48,55,58,62),(44,51,55,60),(46,53,58,60),(43,50,55,58)]
 if mission:notes=[(36,43,48,51),(32,39,44,48),(34,41,46,50),(31,38,43,46)]
 samples=[]
 for i in range(duration*RATE):
  t=i/RATE;section=int(t//12);local=t%12;envelope=min(1,local/2,(12-local)/2);sound=0
  for j,note in enumerate(notes[section]):
   f=440*2**((note-69)/12);phase=TAU*f*t
   sound+=(math.sin(phase)+.22*math.sin(phase*2+.3*math.sin(t*.2))+ .10*math.sin(phase*3))*.036*envelope*(.8+.2*math.sin(TAU*t/12+j))
  pulse=max(0,math.sin(TAU*t*(1 if mission else .25)))**8
  sound+=math.sin(TAU*55*t)*pulse*(.023 if mission else .007)
  sound+=math.sin(TAU*110*t)*.012*math.sin(math.pi*t/duration)**2
  samples.append(sound)
 write(name,samples)
def ambient(theme):
 rng=random.Random(420+theme);samples=[];smooth=0;duration=24
 for i in range(duration*RATE):
  t=i/RATE;smooth=.985*smooth+.015*rng.uniform(-1,1)
  tone=math.sin(TAU*(42+theme*7)*t)*.027+math.sin(TAU*(84+theme*14)*t)*.007
  breathing=.55+.45*math.sin(TAU*t/6)**2;edge=min(1,t/1,(duration-t)/1)
  samples.append((smooth*.20+tone)*breathing*edge)
 write('ambience-'+str(theme),samples)
effects=['click','move','shot','reload','interact','ability','supply','hit','round','emergency','extract','warning','shield','heal','scan','dash','emp','grenade','smoke','loot','echo','door','objective','upgrade','hover','error','drone','boss','oxygen','radio']
used={'click','move','shot','reload','interact','ability','supply','hit','round','emergency','extract','warning','upgrade'}
for index,name in enumerate(effects):
 if name not in used:continue
 rng=random.Random(123+index);duration=.09 if name in ['click','hover'] else .32 if name in ['move','reload','interact'] else .65
 if name in ['emergency','extract','echo','boss']:duration=1.1
 data=[];smooth=0
 for i in range(int(duration*RATE)):
  t=i/RATE;progress=t/duration;envelope=(1-progress)**2*min(1,t*200)
  smooth=.8*smooth+.2*rng.uniform(-1,1)
  if name in ['shot','hit','grenade','move','door','reload']:
   val=(smooth*.48+math.sin(TAU*(90+250*math.exp(-t*25))*t)*.19)*envelope
  else:
   base=210+index*23;frequency=base*(1+progress*.5)
   if name in ['emergency','warning','error']:frequency=400 if int(t*8)%2==0 else 320
   val=(math.sin(TAU*frequency*t)*.15+math.sin(TAU*frequency*1.502*t)*.045+smooth*.03)*envelope
  data.append(val)
 write(name,data)
music('menu');music('mission',True)
for theme in range(3):ambient(theme)
print(f'Wrote {len(used)} effects, 2 original ambient tracks, 3 station soundscapes')
