#!/usr/bin/env python3
"""
Animated renderer-optimization graph for Edge Roll, in the style of the reference
speedup chart: a stepped line rising through annotated optimization milestones,
real per-second FPS samples scattered around each step, and reference threshold
lines. Renders PNG frames (2x supersampled) -> ffmpeg makes mp4 + gif.

All numbers are REAL measurements from perf/data/results.csv + raw_*.txt, on a
throttled Moto G7 Power (GPU 216 MHz, CPU 614 MHz, 4 cores) against a 1020-tile
stress field (render distance x2.4 vs the shipped view).
"""
import os, re, math
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "..", "data")
FRAMES = os.path.join(HERE, "frames")
os.makedirs(FRAMES, exist_ok=True)

SS = 2
W, H = 1200 * SS, 680 * SS

BG    = (255, 255, 255); PANEL = (251, 250, 255)
INK   = (30, 27, 58);    MUTED = (120, 122, 148); FAINT = (176, 178, 198)
GRID  = (234, 234, 244)
LINE  = (91, 75, 230);   LINE_DK = (67, 56, 202); DOT = (172, 164, 238)
VSYNC = (236, 72, 118);  SMOOTH = (13, 148, 136); BASEC = (156, 163, 178)

def font(px, bold=True):
    p = "/usr/share/fonts/truetype/dejavu/DejaVuSans%s.ttf" % ("-Bold" if bold else "")
    return ImageFont.truetype(p, int(px * SS))
F_TITLE=font(25); F_SUB=font(14,False); F_AX=font(14,False)
F_SPD=font(20); F_NAME=font(14,False); F_TICK=font(13,False)
F_THR=font(13); F_BIG=font(34); F_SMALL=font(11,False)

def read_win_fps(fn):
    p = os.path.join(DATA, fn)
    if not os.path.exists(p): return []
    out = []
    for line in open(p):
        if " win " in line:
            m = re.search(r"fps=([0-9.]+)", line)
            if m: out.append(float(m.group(1)))
    return out

# name, fps, draw-label, raw file, annotation target (x in step units, y in fps), text-anchor
STEPS = [
    ("Baseline",                   6.91, "1023 draw calls",       "raw_05_heavy_pertile_nocull.txt", 0.16, 17.5, 'la'),
    ("Frustum culling",           10.19, "663 draw calls",        "raw_06_heavy_pertile_cull.txt",   1.12, 20.5, 'la'),
    ("Batched tile mesh",         46.72, "1023 → 4 draw calls",   "raw_probe_17x60.txt",             0.30, 37.0, 'la'),
    ("Update-loop culling",       48.38, "4 draw calls",          "raw_03_updatecull_17x60.txt",     2.25, 58.0, 'la'),
    ("Hidden-face LOD",           49.81, "4 draw calls",          "raw_04_facereduce_17x60.txt",     3.40, 56.5, 'la'),
    ("Rest-free tile fade-in",    50.26, "4 draw calls · +anim",  "raw_08_fadein_opt_17x60.txt",     4.50, 42.5, 'la'),
]
BASE = STEPS[0][1]
SCAT = [read_win_fps(s[3]) for s in STEPS]
N = len(STEPS)

PX0, PX1 = 150*SS, 800*SS
PY0, PY1 = 120*SS, 560*SS
YMAX = 62.0
def xpix(i):  return PX0 + (PX1-PX0) * (i/(N-1))
def ypix(f):  return PY1 - (PY1-PY0) * (f/YMAX)
THRX = PX1 + 176*SS   # where threshold labels sit

def smooth(x): return 0.0 if x<=0 else 1.0 if x>=1 else x*x*(3-2*x)
def ease_io(x):
    x=max(0.0,min(1.0,x)); return 4*x*x*x if x<0.5 else 1-pow(-2*x+2,3)/2
def rgba(c,a): return (c[0],c[1],c[2],int(max(0,min(1,a))*255))

def dashed(d,x0,y0,x1,y1,color,width,dash=14*SS,gap=10*SS):
    ln=math.hypot(x1-x0,y1-y0)
    if ln==0: return
    ux,uy=(x1-x0)/ln,(y1-y0)/ln; s=0
    while s<ln:
        e=min(s+dash,ln); d.line([x0+ux*s,y0+uy*s,x0+ux*e,y0+uy*e],fill=color,width=width); s+=dash+gap

def T(d,xy,s,f,fill,anchor="la"): d.text(xy,s,font=f,fill=fill,anchor=anchor)

TOTAL = 104

def draw_frame(fi):
    t = fi/(TOTAL-1)
    axis_a = smooth(t/0.09)
    sweepR = ease_io((t-0.10)/0.66) * (N-1+0.6)   # leading edge (overshoots so last step fully reveals)
    sweep  = min(sweepR, N-1)

    img = Image.new("RGB",(W,H),BG); d = ImageDraw.Draw(img,"RGBA")
    d.rounded_rectangle([20*SS,20*SS,W-20*SS,H-20*SS],radius=18*SS,fill=PANEL)

    T(d,(52*SS,38*SS),"Edge Roll — renderer optimization under a hardware throttle",F_TITLE,INK)
    T(d,(52*SS,74*SS),"Frame rate on a 1020-tile stress field (render distance ×2.4)  ·  Moto G7 Power · GPU pinned 216 MHz · CPU 614 MHz · 4 cores",F_SUB,MUTED)

    # y grid + labels
    for f in range(0,61,10):
        y=ypix(f); d.line([PX0-14*SS,y,THRX-8*SS,y],fill=rgba(GRID,axis_a),width=1*SS)
        T(d,(PX0-24*SS,y),str(f),F_TICK,rgba(MUTED,axis_a),"rm")
    lab=Image.new("RGBA",(300*SS,24*SS),(0,0,0,0))
    ImageDraw.Draw(lab).text((0,0),"Frames per second  (higher = better)",font=F_AX,fill=rgba(MUTED,axis_a))
    lab=lab.rotate(90,expand=True); img.paste(lab,(28*SS,(PY0+PY1)//2-150*SS),lab)

    # threshold reference lines
    def thr(f,color,label,sub):
        y=ypix(f); a=smooth((t-0.05)/0.08)
        dashed(d,PX0,y,THRX-8*SS,y,rgba(color,a*0.9),2*SS)
        T(d,(THRX,y-9*SS),label,F_THR,rgba(color,a),"lm"); T(d,(THRX,y+9*SS),sub,F_SMALL,rgba(MUTED,a),"lm")
    thr(60,VSYNC,"60 FPS","vsync ceiling"); thr(30,SMOOTH,"30 FPS","smooth-play floor")
    yb=ypix(BASE); ab=smooth((t-0.05)/0.08)
    dashed(d,PX0,yb,THRX-8*SS,yb,rgba(BASEC,ab*0.8),2*SS,dash=4*SS,gap=7*SS)
    T(d,(THRX,yb),"baseline 6.9",F_SMALL,rgba(BASEC,ab),"lm")

    # scatter (real per-second samples)
    for i,samples in enumerate(SCAT):
        a=smooth((sweepR-i)/0.5)
        if a<=0 or not samples: continue
        cx=xpix(i)
        for k,v in enumerate(samples):
            jx=((k*37%41)/41-0.5)*32*SS; r=4.0*SS
            d.ellipse([cx+jx-r,ypix(v)-r,cx+jx+r,ypix(v)+r],fill=rgba(DOT,a*0.5))

    # main line up to sweep
    pts=[(xpix(i),ypix(STEPS[i][1])) for i in range(N) if i<=sweep]
    fl=math.floor(sweep)
    if 0<=fl<N-1 and sweep<N-1:
        fr=sweep-fl
        pts.append((xpix(fl)+(xpix(fl+1)-xpix(fl))*fr, ypix(STEPS[fl][1])+(ypix(STEPS[fl+1][1])-ypix(STEPS[fl][1]))*fr))
    if len(pts)>=2: d.line(pts,fill=LINE,width=4*SS,joint="curve")

    # markers + fanned annotations
    for i,(name,fps,draws,_,tx,ty,anch) in enumerate(STEPS):
        ap=smooth((sweepR-i+0.25)/0.3)
        if ap<=0: continue
        x,y=xpix(i),ypix(fps); r=(7+3*ap)*SS
        d.ellipse([x-r,y-r,x+r,y+r],outline=LINE_DK,width=3*SS,fill=rgba(BG,1))
        d.ellipse([x-3*SS,y-3*SS,x+3*SS,y+3*SS],fill=LINE_DK)
        aa=smooth((sweepR-i-0.05)/0.3)
        if aa<=0: continue
        axp,ayp=xpix(tx),ypix(ty)
        d.line([x,y,axp,ayp+ (6*SS if ayp>y else -2*SS)],fill=rgba(FAINT,aa*0.85),width=2*SS)
        hero = (i==2)
        T(d,(axp,ayp),"%.1f×"%(fps/BASE),F_BIG if hero else F_SPD,rgba(LINE_DK,aa),anch)
        yo = 30 if hero else 20
        T(d,(axp,ayp+yo*SS),name,F_NAME,rgba(INK,aa),anch)
        T(d,(axp,ayp+(yo+18)*SS),draws,F_SMALL,rgba(MUTED,aa),anch)

    # x ticks
    for i in range(N):
        a=smooth((sweepR-i+0.3)/0.4); T(d,(xpix(i),PY1+22*SS),str(i),F_TICK,rgba(MUTED,a),"mm")
    T(d,((PX0+PX1)//2,PY1+46*SS),"optimization step →",F_AX,rgba(MUTED,axis_a),"mm")

    # end headline in the empty lower-right
    eA=smooth((sweepR-(N-1)+0.1)/0.5)
    if eA>0:
        hx,hy=xpix(3.65),ypix(15)
        T(d,(hx,hy),"7.3× faster",F_BIG,rgba(LINE_DK,eA),"ma")
        T(d,(hx,hy+34*SS),"6.9 → 50 FPS   ·   1023 → 4 draw calls",F_SMALL,rgba(MUTED,eA),"ma")

    T(d,(52*SS,H-38*SS),"Shipped 234-tile view: batching took the same throttle from 24.9 → 60 FPS (vsync-locked).   Unthrottled device: 60 FPS throughout.",F_SMALL,rgba(MUTED,axis_a),"lm")
    return img.resize((W//SS,H//SS),Image.LANCZOS)

if __name__=="__main__":
    for fi in range(TOTAL): draw_frame(fi).save(os.path.join(FRAMES,"f%03d.png"%fi))
    print("rendered",TOTAL,"frames")
