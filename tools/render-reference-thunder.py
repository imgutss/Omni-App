#!/usr/bin/env python3
"""Offline PCM rendering of omni-zap-js thunder() noise, filter and envelope parameters.
Native Android plays the result as sonification, honoring system volume and silent mode.
"""
from pathlib import Path
import numpy as np
from scipy.signal import lfilter
import wave
rate=44100;rng=np.random.default_rng(131);N=rate*3
white=rng.uniform(-1,1,N);brown=lfilter([.02/1.02],[1,-1/1.02],white)*3.5
out=Path(__file__).resolve().parents[1]/'V2rayNG/app/src/main/assets/omni-reference/audio';out.mkdir(parents=True,exist_ok=True)
def filtered(data,kind,f):
 w=2*np.pi*f/rate;c=np.cos(w);a=np.sin(w)/(2*10**(1/20))
 b=np.array([(1-c)/2,1-c,(1-c)/2])if kind=='lowpass'else np.array([(1+c)/2,-(1+c),(1+c)/2])
 return lfilter(b/(1+a),[1,-2*c/(1+a),(1-a)/(1+a)],data)
for on in [True,False]:
 output=np.zeros(rate*3)
 def bus(data,kind,f,g,start,attack,decay):
  offset=int(rng.uniform(0,1.5)*rate);duration=int((attack+decay+.1)*rate);signal=filtered(data,kind,f)[offset:offset+duration]
  time=np.arange(len(signal))/rate;env=np.where(time<attack,g*time/max(attack,1e-6),g*np.exp(np.log(.001/g)*(time-attack)/decay));env[time>attack+decay]=0
  i=int(start*rate);n=min(len(signal),len(output)-i);output[i:i+n]+=signal[:n]*env[:n]
 bus(white,'highpass',1400,.7 if on else .25,0,.002,.14)
 bus(brown,'lowpass',90,3 if on else 1.6,0,.02,.8)
 bus(brown,'lowpass',260,2.4 if on else 1.3,.12,.08,2.2 if on else 1.2)
 bus(brown,'lowpass',200,1.5 if on else .7,.55,.2,1.8 if on else .9)
 if not on:
  t=np.arange(int(.75*rate))/rate;phase=np.cumsum(420*(40/420)**np.minimum(1,t/.7))/rate
  sweep=(2*(phase%1)-1)*.06*np.exp(np.log(.001/.06)*t/.7);output[:len(t)]+=sweep
 output*=.9 if on else .55
 # Gentle compressor; do not hard-clip loud transients.
 output=np.tanh(output*1.5)*.7
 pcm=(np.clip(output,-1,1)*32767).astype('<i2')
 with wave.open(str(out/('power-on.wav'if on else'power-off.wav')),'wb')as f:f.setnchannels(1);f.setsampwidth(2);f.setframerate(rate);f.writeframes(pcm.tobytes())
 print('Rendered original thunder parameters:',on,len(pcm)/rate,'seconds')
