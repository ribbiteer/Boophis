# SPDX-License-Identifier: GPL-3.0-or-later
# Exports fixture art from assets/work/fix (IrisEcho outputs) to fixtures/media. Needs Pillow.
import os
from PIL import Image
ROOT=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
S=os.path.join(ROOT,'assets','work','fix')+'/'; M=os.path.join(ROOT,'fixtures','media')+'/'
def fit(im,w,h):
    r=w/h; W,H=im.size
    if W/H>r: nw=int(H*r); im=im.crop(((W-nw)//2,0,(W-nw)//2+nw,H))
    else: nh=int(W/r); im=im.crop((0,(H-nh)//2,W,(H-nh)//2+nh))
    return im.resize((w,h),Image.LANCZOS)
def out(src,dst,w,h):
    im=fit(Image.open(S+src+'.png').convert('RGB'),w,h); q=88
    while True:
        im.save(M+dst,quality=q,optimize=True)
        import os
        if os.path.getsize(M+dst)<230000 or q<60: break
        q-=6
PH=M+'Movies/Paper Harbor (2021)/'
for src,dst,w,h in [
 ('poster-paper-harbor','Movies/Paper Harbor (2021)/poster.jpg',680,1020),
 ('fanart-paper-harbor','Movies/Paper Harbor (2021)/fanart.jpg',1280,720),
 ('poster-midnight-orchard','Movies/Midnight Orchard (2018)/folder.jpg',680,1020),
 ('backdrop-midnight-orchard','Movies/Midnight Orchard (2018)/backdrop.jpg',1280,720),
 ('poster-saltmarsh-station','Shows/Saltmarsh Station/poster.jpg',680,1020),
 ('fanart-saltmarsh-station','Shows/Saltmarsh Station/fanart.jpg',1280,720),
 ('season01-poster','Shows/Saltmarsh Station/season01-poster.jpg',680,1020),
 ('season02-poster','Shows/Saltmarsh Station/season02-poster.jpg',680,1020)]: out(src,dst,w,h)
T={(1,1):"The Lantern Keeper",(1,2):"Late Arrival",(1,3):"Unclaimed Parcels",(2,1):"Frozen Line",(2,2):"Signal in the Rain",(2,3):"The Waiting Room"}
for (s,e),t in T.items():
    out(f'thumb-s0{s}e0{e}',f'Shows/Saltmarsh Station/Season 0{s}/Saltmarsh Station S0{s}E0{e} - {t}-thumb.jpg',640,360)
