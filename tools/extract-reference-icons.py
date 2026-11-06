#!/usr/bin/env python3
"""Extract actual final-layer 3D SVG artwork, never replace it with Material glyphs.
Usage: python tools/extract-reference-icons.py /path/to/Omni-0.13.1.html
Requires Node, beautifulsoup4, cairosvg, Pillow. No generated or remotely fetched artwork.
"""
from pathlib import Path
import sys,re,json,hashlib,subprocess,tempfile,io
from bs4 import BeautifulSoup
import cairosvg
from PIL import Image,ImageFilter
source=Path(sys.argv[1]);html=source.read_text();soup=BeautifulSoup(html,'html.parser')
script=next(t.get_text()for t in soup.find_all('script')if 'var u=c=>'in t.get_text()and'IC=['in t.get_text())
marker="dr.querySelectorAll('.dw-hd i')"
script=script.replace(marker,"globalThis.output={icons:IC,defs:globalThis.defs};return;"+marker,1)
harness="globalThis.document={querySelector:()=>({}),body:{insertAdjacentHTML:(_,s)=>{globalThis.defs=s}}};\n"+script+"\nconsole.log(JSON.stringify(globalThis.output))"
data=json.loads(subprocess.check_output(['node','-e',harness],text=True))
root=Path(__file__).resolve().parents[1];out=root/'V2rayNG/app/src/main/assets/omni-reference';out.mkdir(parents=True,exist_ok=True)
# These are the final artwork's original geometric subjects, not current menu positions.
names=['subscriptions','per-app','routing','assets','settings','hub','doctor','scanner','logs','update','backup','about','effects','language','theme']
assert len(data['icons'])==len(names)
kotlin=(root/'V2rayNG/app/src/main/java/com/v2ray/ang/ui/compose/OmniAppearance.kt').read_text()
skins={name:accent for name,accent in re.findall(r'\w+\("([a-z]+)", R.string.\w+, Color\(0xFF([0-9A-Fa-f]{6})\)',kotlin)}
def colorize(svg,accent):
 def mix(m):
  p=int(m[1])/100;other=m[2];other=other[1:];other=other if len(other)==6 else ''.join(c*2 for c in other)
  a=tuple(int(accent[i:i+2],16)for i in(0,2,4));b=tuple(int(other[i:i+2],16)for i in(0,2,4));return '#'+''.join(f'{round(x*p+y*(1-p)):02x}'for x,y in zip(a,b))
 svg=re.sub(r'color-mix\(in srgb,var\(--acc\) (\d+)%,(#[0-9a-fA-F]+)\)',mix,svg).replace('var(--acc)','#'+accent)
 # SVG/Cairo portability: SVG 1.1 uses stop-opacity, not CSS eight-digit hex.
 svg=svg.replace("stop-color:#ffffffe6","stop-color:#ffffff;stop-opacity:.90196").replace("stop-color:#ffffff00","stop-color:#ffffff;stop-opacity:0")
 return svg
manifest={'referenceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),'source':'Omni-0.13.1.html / final script-30 IC artwork','icons':names,'skins':skins,'pngPixels':320,'logicalArtworkDp':64,'logicalPaddingDp':8,'files':{}}
defs=re.search(r'<defs>.*?</defs>',data['defs'],re.S).group(0)
for skin,accent in skins.items():
 folder=out/'icons'/skin;folder.mkdir(parents=True,exist_ok=True)
 for name,art in zip(names,data['icons']):
  svg=colorize('<svg xmlns="http://www.w3.org/2000/svg" viewBox="-8 -8 80 80">'+defs+art+'</svg>',accent)
  raw=cairosvg.svg2png(bytestring=svg.encode(),output_width=320,output_height=320)
  image=Image.open(io.BytesIO(raw)).convert('RGBA');alpha=image.getchannel('A');shadow=Image.new('RGBA',image.size,(0,0,0,0));shadow.putalpha(alpha.filter(ImageFilter.GaussianBlur(16)).point(lambda a:round(a*.45)))
  composed=Image.new('RGBA',image.size);composed.alpha_composite(shadow,(0,20));composed.alpha_composite(image)
  target=folder/(name+'.png');composed.save(target,optimize=True);manifest['files'][str(target.relative_to(out))]=hashlib.sha256(target.read_bytes()).hexdigest()
(out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');print('Extracted',len(manifest['files']),'actual reference icons;',len(skins),'skins')
