"""Original native-pixel, stateless pattern implementations. No imported clips."""

from dataclasses import dataclass
import math

import numpy as np
from PIL import Image, ImageDraw

PATTERNS = {}
PALETTE = np.array([[255,255,255], [255,255,0], [0,255,255], [0,255,0],
                    [255,0,255], [255,0,0], [0,0,255], [0,0,0]], dtype=np.uint8)


def register(number, name, group, defaults=None, description=""):
    def decorate(fn):
        PATTERNS[name] = {"id": f"p{number:02}", "name": name, "group": group,
                          "version": 1, "defaults": defaults or {},
                          "description": description, "render": fn}
        return fn
    return decorate


def resolve(name):
    if not isinstance(name,str):
        raise ValueError("Pattern must be a stable ID or catalogue name")
    if name in PATTERNS:
        return PATTERNS[name]
    for p in PATTERNS.values():
        if name == p["id"]:
            return p
    raise ValueError(f"Unknown pattern {name!r}; run list")


def parameters(pattern, supplied):
    defaults = pattern["defaults"]
    if not isinstance(supplied, dict) or set(supplied) - set(defaults):
        raise ValueError(f"{pattern['name']}: unknown parameters; allowed: {list(defaults)}")
    p = {**defaults, **supplied}
    for k, v in p.items():
        default = defaults[k]
        if isinstance(default, bool):
            valid = type(v) is bool
        elif isinstance(default, str):
            valid = isinstance(v, str) and bool(v)
        elif isinstance(default, int):
            valid = type(v) is int and v > 0
        else:
            valid = type(v) in (int, float) and math.isfinite(v) and v >= 0
        if not valid:
            raise ValueError(f"{pattern['name']}: invalid {k}={v!r}")
        if k in ("density", "detail") and not 0 < v <= 16:
            raise ValueError(f"{k} must be in (0,16]")
        if k == "entropy" and not 0 <= v <= 1:
            raise ValueError("entropy must be in [0,1]")
        if k == "speed" and v > 100:
            raise ValueError("speed must be in [0,100]")
    name = pattern["name"]
    if name=="native_checkerboard" and p["cell"] not in (1,2):
        raise ValueError("native_checkerboard cell must be 1 or 2 native pixels")
    if name=="grayscale_steps" and p["steps"]<2:
        raise ValueError("grayscale_steps requires at least 2 steps")
    if name=="dissolves_fades" and p["period_frames"]<6:
        raise ValueError("dissolves_fades period_frames must be >=6")
    if name=="independent_particles" and p["trails"]>16:
        raise ValueError("trails must be 1..16")
    if name=="low_contrast" and p["contrast"]>64:
        raise ValueError("low_contrast contrast must be <=64")
    if name=="precipitation" and p["style"] not in ("snow","rain"):
        raise ValueError("precipitation style must be snow or rain")
    if "cell_size" in p and (p["cell_size"]<2 or p["cell_size"]%2):
        raise ValueError("Marker cell_size must be an even integer >=2")
    if name=="scrolling_text":
        from .font import GLYPHS
        if any(char not in GLYPHS for char in p["text"].upper()):
            raise ValueError("Text contains unsupported bitmap-font glyphs; see README")
    return p


@dataclass(frozen=True)
class Context:
    width: int
    height: int
    fps: int
    frame: int
    seed: int
    params: dict
    pattern_number: int

    @property
    def t(self):
        return self.frame / self.fps

    @property
    def grid(self):
        y, x = np.ogrid[:self.height, :self.width]
        return x, y

    def rng(self, counter=0):
        return np.random.Generator(np.random.PCG64(np.random.SeedSequence([self.seed, self.pattern_number, counter])))


def gray(field, c):
    a = np.broadcast_to(field, (c.height, c.width))
    return np.repeat(np.clip(np.rint(a), 0, 255).astype(np.uint8)[..., None], 3, axis=2)


def rgb(fields, c):
    return np.stack([np.broadcast_to(v, (c.height, c.width)) for v in fields], -1).clip(0,255).round().astype(np.uint8)


def canvas(c, background=(12,16,24)):
    im = Image.new("RGB", (c.width, c.height), background)
    return im, ImageDraw.Draw(im)


@register(1, "color_bars", "calibration", {"patches": True}, "BT.709 signal-space 100% bars and calibrated lower patches")
def color_bars(c):
    x, _ = c.grid
    a = np.broadcast_to(PALETTE[np.minimum(x * 8 // c.width, 7)], (c.height,c.width,3)).copy()
    if c.params["patches"]:
        values = np.array([0,16,32,64,128,192,235,255], np.uint8)
        a[c.height * 3 // 4:] = values[np.arange(c.width) * 8 // c.width, None]
    return a


@register(2, "grayscale_steps", "calibration", {"steps": 16})
def grayscale_steps(c):
    x, _ = c.grid
    steps = c.params["steps"]
    if steps < 2:
        raise ValueError("grayscale_steps requires at least 2 steps")
    return gray((x * steps // c.width) * 255 / (steps-1),c)


@register(3, "grayscale_gradient", "calibration", {"vertical": False})
def grayscale_gradient(c):
    x,y = c.grid
    return gray(y*255/max(1,c.height-1) if c.params["vertical"] else x*255/max(1,c.width-1),c)


@register(4, "color_gradients", "calibration", {"secondary": True})
def color_gradients(c):
    x,y = c.grid
    ramp = x*255/max(1,c.width-1)
    band = np.minimum(y*6//c.height,5)
    channels = np.array([[1,0,0],[0,1,0],[0,0,1],[1,1,0],[0,1,1],[1,0,1]])
    if not c.params["secondary"]:
        band %= 3
    return rgb([ramp*channels[band,i] for i in range(3)],c)


@register(5, "moving_gradients", "motion", {"speed": 1.0, "detail": 1.0})
def moving_gradients(c):
    x,y = c.grid
    phase = c.t*c.params["speed"]
    return rgb([127.5+127.5*np.sin(2*np.pi*(x/c.width*c.params["detail"]+y/c.height*(i+1)/3-phase*(i+1)/3)+i*2)
                for i in range(3)],c)


@register(6, "low_contrast", "calibration", {"contrast": 4.0})
def low_contrast(c):
    im,d = canvas(c,(128,128,128))
    delta = c.params["contrast"]
    if delta > 64:
        raise ValueError("low_contrast contrast must be <=64 code values")
    for i in range(1,5):
        level = int(round(128+delta*(i-2.5)/2))
        x,y = i*c.width//5,c.height//2
        radius = max(1,min(c.width,c.height)//10)
        if i%2:
            d.ellipse((x-radius,y-radius,x+radius,y+radius),fill=(level,)*3)
        else:
            d.rectangle((x-radius,y-radius,x+radius,y+radius),fill=(level,)*3)
    return np.asarray(im)


@register(7, "color_fades", "calibration", {"speed": 1.0, "luminance_only": False})
def color_fades(c):
    phase = 2*np.pi*c.t*c.params["speed"]/4
    levels = [127.5+127.5*np.sin(phase+i*2*np.pi/3) for i in range(3)]
    return rgb([levels[0]]*3 if c.params["luminance_only"] else levels,c)


@register(8, "rotating_wedges", "detail", {"speed": 1.0, "wedges": 24})
def rotating_wedges(c):
    x,y = c.grid
    angle = np.arctan2(y-(c.height-1)/2,x-(c.width-1)/2)-c.t*c.params["speed"]
    return gray((np.floor(angle*c.params["wedges"]/(2*np.pi))%2)*255,c)


@register(9, "frequency_circles", "detail", {"detail": 1.0})
def frequency_circles(c):
    x,y = c.grid
    radius = np.hypot(x-(c.width-1)/2,y-(c.height-1)/2)
    return gray(127.5+127.5*np.sin(radius**1.6*c.params["detail"]/10),c)


@register(10, "zone_plate", "detail", {"speed": 1.0, "detail": 1.0})
def zone_plate(c):
    x,y = c.grid
    cx = c.width*(0.5+0.2*np.sin(c.t*c.params["speed"]))
    cy = c.height*(0.5+0.2*np.cos(c.t*c.params["speed"]*0.7))
    phase = ((x-cx)**2+(y-cy)**2)*np.pi*c.params["detail"]/max(c.width,c.height)
    return gray(127.5+127.5*np.cos(phase),c)


@register(11, "line_pairs", "detail", {"max_period": 16})
def line_pairs(c):
    x,y = c.grid
    period = np.maximum(1,np.rint(c.params["max_period"]*(1-y/max(1,c.height-1)))).astype(int)
    horizontal = x < c.width//2
    return gray(np.where(horizontal,(x//period)%2,(y//period)%2)*255,c)


@register(12, "finer_checkerboards", "detail", {"bands": 6})
def finer_checkerboards(c):
    x,y = c.grid
    band = np.minimum(x*c.params["bands"]//c.width,c.params["bands"]-1)
    cell = np.maximum(1,2**np.minimum(c.params["bands"]-band-1,16))
    return gray(((x//cell+y//cell)%2)*255,c)


@register(13, "diagonals_grids", "detail", {"speed": 1.0, "spacing": 8})
def diagonals_grids(c):
    x,y = c.grid
    angle = c.t*c.params["speed"]/3+0.3
    u = x*np.cos(angle)+y*np.sin(angle)
    v = -x*np.sin(angle)+y*np.cos(angle)
    thin = (np.mod(u,c.params["spacing"])<1)|(np.mod(v,c.params["spacing"])<1)
    return gray(thin*255,c)


@register(14, "native_checkerboard", "detail", {"cell": 1, "speed": 1.0})
def native_checkerboard(c):
    if c.params["cell"] not in (1,2):
        raise ValueError("native_checkerboard cell must be exactly 1 or 2 native pixels")
    x,y = c.grid
    shift = math.floor(c.frame*c.params["speed"])
    return gray((((x-shift)//c.params["cell"]+y//c.params["cell"])%2)*255,c)


@register(15, "pulsing_grid", "detail", {"spacing": 4, "speed": 1.0})
def pulsing_grid(c):
    x,y = c.grid
    grid = (x%c.params["spacing"]==0)|(y%c.params["spacing"]==0)
    light = 127.5+127.5*np.sin(c.t*c.params["speed"]*2*np.pi)
    return gray(np.where(grid,light,24),c)


@register(16, "breathing_circles", "motion", {"speed": 1.0, "density": 1.0})
def breathing_circles(c):
    x,y = c.grid
    radius = np.hypot(x-c.width/2,y-c.height/2)
    spacing = max(2,min(c.width,c.height)/8/c.params["density"])
    scale = 1+0.4*np.sin(c.t*c.params["speed"]*2)
    return gray((np.mod(radius/scale,spacing)<1.5)*255,c)


@register(17, "zoom_checkerboard", "motion", {"speed": 1.0, "cell": 8})
def zoom_checkerboard(c):
    x,y = c.grid
    scale = c.params["cell"]*np.exp(np.sin(c.t*c.params["speed"])*1.2)
    return gray(((np.floor((x-c.width/2)/scale)+np.floor((y-c.height/2)/scale))%2)*255,c)


@register(18, "rotating_polygons", "motion", {"speed": 1.0, "count": 5})
def rotating_polygons(c):
    im,d = canvas(c)
    for i in range(c.params["count"]):
        radius = min(c.width,c.height)*(0.46-0.35*i/max(1,c.params["count"]-1))
        sides = 3+i%5
        angle = c.t*c.params["speed"]*(1+i/4)*(-1 if i%2 else 1)
        points = [(c.width/2+radius*np.cos(angle+j*2*np.pi/sides),
                   c.height/2+radius*np.sin(angle+j*2*np.pi/sides)) for j in range(sides)]
        d.polygon(points,outline=tuple(int(v) for v in PALETTE[i%7]),width=1+i%3)
    return np.asarray(im)


@register(19, "independent_bars", "motion", {"speed": 1.0, "count": 6})
def independent_bars(c):
    im,d = canvas(c)
    for i in range(c.params["count"]):
        width = max(1,c.width//40)
        left = int((c.width*(i+0.5)/c.params["count"]+c.t*c.params["speed"]*(i+1)*c.width/9)%c.width)
        top,bottom = i*c.height//c.params["count"],(i+1)*c.height//c.params["count"]-1
        if bottom>=top:
            d.rectangle((left,top,min(c.width-1,left+width),bottom),fill=tuple(int(v) for v in PALETTE[i%7]))
    return np.asarray(im)


def bounce(value, extent):
    if extent <= 0:
        return 0.0
    return extent-abs((value%(2*extent))-extent)


@register(20, "bouncing_balls", "motion", {"speed": 1.0, "count": 12})
def bouncing_balls(c):
    im,d = canvas(c)
    rng = c.rng()
    for i in range(c.params["count"]):
        radius = max(1,min(c.width,c.height)*rng.uniform(0.015,0.06))
        vx,vy = rng.uniform(-0.8,0.8,2)
        ox,oy = rng.random(2)
        x = radius+bounce(ox*c.width+c.t*c.params["speed"]*vx*c.width,c.width-2*radius)
        y = radius+bounce(oy*c.height+c.t*c.params["speed"]*vy*c.height,c.height-2*radius)
        color = tuple(int(v) for v in rng.integers(48,256,3))
        d.ellipse((x-radius,y-radius,x+radius,y+radius),fill=color)
    return np.asarray(im)


@register(21, "crossing_objects", "motion", {"speed": 1.0})
def crossing_objects(c):
    im,d = canvas(c)
    size = max(2,min(c.width,c.height)//5)
    for i in range(4):
        x = (c.width*(i+1)/5+(-1 if i%2 else 1)*c.t*c.params["speed"]*c.width/2)%(c.width+size)-size
        y = c.height*(i+1)/5
        if i%2:
            d.ellipse((x,y-size/2,x+size,y+size/2),fill=(250,70,50))
        else:
            d.rectangle((x,y-size/2,x+size,y+size/2),fill=(40,180,240))
    return np.asarray(im)


@register(22, "monochrome_noise", "entropy", {"entropy": 1.0}, "Independent seeded per-frame monochrome noise; extreme case")
def monochrome_noise(c):
    return gray(128+(c.rng(c.frame+1).random((c.height,c.width))-0.5)*255*c.params["entropy"],c)


@register(23, "colored_noise", "entropy", {"entropy": 1.0}, "Independent seeded per-frame RGB noise; extreme case")
def colored_noise(c):
    return (128+(c.rng(c.frame+1).random((c.height,c.width,3))-0.5)*255*c.params["entropy"]).clip(0,255).round().astype(np.uint8)


@register(24, "noise_tracks", "entropy", {"speed": 1.0, "tracks": 6, "entropy": 1.0})
def noise_tracks(c):
    a = (128+(c.rng().random((c.height,c.width,3))-0.5)*255*c.params["entropy"]).round().astype(np.uint8)
    x,y = np.meshgrid(np.arange(c.width),np.arange(c.height))
    strip = y*c.params["tracks"]//c.height
    shift = np.floor(c.t*c.params["speed"]*(strip+1)*c.width/8).astype(int)
    return a[y,(x-shift)%c.width]


@register(25, "clean_noise_boundary", "entropy", {"speed": 1.0, "entropy": 1.0})
def clean_noise_boundary(c):
    x,_ = c.grid
    boundary = c.width*(0.5+0.45*np.sin(c.t*c.params["speed"]*2))
    clean = render("color_gradients",c.width,c.height,c.fps,c.frame,c.seed)
    noise = render("colored_noise",c.width,c.height,c.fps,c.frame,c.seed,{"entropy":c.params["entropy"]})
    return np.where((x<boundary)[...,None],clean,noise)


@register(26, "grain_edges", "entropy", {"entropy": 0.35, "speed": 1.0})
def grain_edges(c):
    x,y = c.grid
    base = np.broadcast_to(x*210/max(1,c.width-1),(c.height,c.width)).copy()
    base[(x+y+c.frame*c.params["speed"])%max(2,c.width//4)<max(1,c.width//16)] += 45
    grain = (c.rng(c.frame+1).random((c.height,c.width))-0.5)*128*c.params["entropy"]
    return gray(base+grain,c)


COMPLEX = {"density":1.0, "speed":1.0, "detail":1.0, "entropy":0.5}


@register(27, "swirling_ink", "complex", COMPLEX, "Procedural multi-vortex interference; approximate fluid appearance, no Navier-Stokes simulation")
def swirling_ink(c):
    x,y = c.grid
    xn,yn = x/c.width,y/c.height
    field = np.zeros((c.height,c.width))
    rng = c.rng()
    count = max(1,math.ceil(c.params["density"]*3))
    for i in range(count):
        cx,cy = rng.random(2)
        angular,radial,phase = rng.uniform(0.5,2.5,3)
        dx,dy = xn-cx,yn-cy
        radius = np.hypot(dx,dy)
        theta = np.arctan2(dy,dx)
        time = c.t*c.params["speed"]
        twist = theta+angular*time+2*np.sin(radius*12*c.params["detail"]-time*radial+phase)
        wave = np.sin(twist*(2+i%4)+radius*25*c.params["detail"]+phase)
        wave += c.params["entropy"]*np.sin(theta*7+radius*47-time*(i+1)*2.3)
        field += wave*np.exp(-radius*(1+radial))
    field /= math.sqrt(count)
    return rgb([128+115*np.sin(field*2+i*1.9) for i in range(3)],c)


@register(28, "turbulent_clouds", "complex", COMPLEX, "Analytic advected multiscale wave density; approximate turbulence, no physical smoke simulation")
def turbulent_clouds(c):
    x,y = c.grid
    xn,yn = x/c.width,y/c.height
    field = np.zeros((c.height,c.width))
    rng = c.rng()
    count = 2+math.ceil(c.params["density"]*3)
    total = 0
    for i in range(count):
        kx,ky = rng.uniform(-1,1,2)*2**(i%5)*c.params["detail"]*8
        velocity,phase = rng.uniform(-2,2,2)
        amp = 0.58**(i%5)
        t = c.t*c.params["speed"]
        warp = c.params["entropy"]*np.sin(xn*(i+2)*11+yn*17-t*velocity*1.7)
        field += amp*(np.cos(kx*xn+ky*yn+phase+t*velocity+warp)
                      *np.sin(ky*xn-kx*yn-phase-t*velocity*0.73))
        total += amp
    cloud = np.clip(0.5+field/total,0,1)**1.8
    return rgb([20+cloud*170,28+cloud*190,40+cloud*205],c)


def particle_records(c, divisor=160):
    count = max(1,round(c.width*c.height/divisor*c.params["density"]))
    rng = c.rng()
    records = rng.random((count,9))
    return records


def polygon(draw,x,y,radius,angle,sides,color,aspect=1):
    points = [(x+radius*np.cos(angle+j*2*np.pi/sides),
               y+radius*aspect*np.sin(angle+j*2*np.pi/sides)) for j in range(sides)]
    draw.polygon(points,fill=color)


@register(29, "precipitation", "complex", {**COMPLEX,"style":"snow"})
def precipitation(c):
    if c.params["style"] not in ("snow","rain"):
        raise ValueError("precipitation style must be snow or rain")
    im,d = canvas(c,(8,12,22))
    t = c.t*c.params["speed"]
    for ox,oy,vx,vy,size,phase,_,_,_ in particle_records(c):
        speed = 0.2+vy*(0.3+c.params["entropy"])
        x = (ox*c.width+t*(vx-0.5)*c.width/5+
             c.params["entropy"]*np.sin(t*(1+vx*3)+phase*6)*c.width/20)%c.width
        y = (oy*c.height+t*speed*c.height)%c.height
        radius = max(0.5,(0.6+size*2)*c.params["detail"])
        if c.params["style"] == "rain":
            d.line((x,y,x-radius,y+radius*5),fill=(150,185,235),width=max(1,round(radius/2)))
        else:
            shade = int(150+size*105)
            d.ellipse((x-radius,y-radius,x+radius,y+radius),fill=(shade,shade,shade))
    return np.asarray(im)


@register(30, "confetti", "complex", COMPLEX)
def confetti(c):
    im,d = canvas(c,(28,18,30))
    t = c.t*c.params["speed"]
    for i,(ox,oy,vx,vy,size,phase,spin,_,_) in enumerate(particle_records(c,210)):
        fall = t*(0.12+vy*(0.4+c.params["entropy"]*0.4))
        x = (ox*c.width+(vx-0.5)*t*c.width/3+np.sin(fall*5+phase*6)*c.params["entropy"]*c.width/8)%c.width
        y = ((oy+fall+0.03*fall*fall)%1)*c.height
        radius = max(1,(1.5+size*3)*c.params["detail"])
        angle = phase*6+t*(spin-0.5)*12
        color = tuple(int(v) for v in PALETTE[i%6+1])
        polygon(d,x,y,radius,angle,4,color,0.3+0.6*abs(np.cos(angle)))
    return np.asarray(im)


@register(31, "dust_explosion", "complex", {**COMPLEX,"period_frames":24})
def dust_explosion(c):
    im,d = canvas(c,(18,12,6))
    age = (c.frame*c.params["speed"]%c.params["period_frames"])/c.params["period_frames"]
    for ox,oy,vx,vy,size,phase,spin,_,_ in particle_records(c,130):
        theta = ox*2*np.pi+c.params["entropy"]*np.sin(age*8+phase*6)*0.25
        travel = (0.2+vy*0.8)*age*min(c.width,c.height)*0.95
        x = c.width/2+travel*np.cos(theta)+(vx-0.5)*age**2*c.width*c.params["entropy"]/6
        y = c.height/2+travel*np.sin(theta)+age**2*c.height*0.2
        radius = max(0.5,(0.6+size*2)*c.params["detail"]*(1-age*0.5))
        color = (int(245-age*110),int(150+oy*80-age*70),int(45+spin*70))
        polygon(d,x,y,radius,spin*6+age*10,3+int(size*3),color)
    return np.asarray(im)


@register(32, "independent_particles", "complex", {**COMPLEX,"trails":1})
def independent_particles(c):
    if c.params["trails"] > 16:
        raise ValueError("trails must be 1..16")
    im,d = canvas(c,(7,15,14))
    for i,(ox,oy,vx,vy,size,phase,spin,_,_) in enumerate(particle_records(c,180)):
        radius = max(1,(1+size*2.5)*c.params["detail"])
        color = PALETTE[i%7]
        for trail in reversed(range(c.params["trails"])):
            t = (c.frame-trail)/c.fps*c.params["speed"]
            jitter = c.params["entropy"]*np.sin(t*(1+phase*5)+ox*8)
            x = bounce(ox*c.width+(vx-0.5)*t*c.width+jitter*c.width/16,c.width-1)
            y = bounce(oy*c.height+(vy-0.5)*t*c.height-jitter*c.height/12,c.height-1)
            angle = phase*6+t*(spin-0.5)*8
            shade = tuple(int(v/(1+trail*0.7)) for v in color)
            polygon(d,x,y,radius,angle,3+i%4,shade)
    return np.asarray(im)


@register(33, "blinking_rectangles", "transitions", {"count":24,"hold_frames":3})
def blinking_rectangles(c):
    im,d = canvas(c,(25,25,25))
    geometry = c.rng().random((c.params["count"],4))
    state = c.rng(c.frame//c.params["hold_frames"]+1)
    for ox,oy,sx,sy in geometry:
        on = state.random()>0.4
        color = tuple(int(v) for v in state.integers(0,256,3))
        if on:
            x,y = int(ox*c.width),int(oy*c.height)
            d.rectangle((x,y,min(c.width-1,x+int((sx*0.2+0.02)*c.width)),
                         min(c.height-1,y+int((sy*0.2+0.02)*c.height))),fill=color)
    return np.asarray(im)


@register(34, "hard_cuts", "transitions", {"hold_frames":5})
def hard_cuts(c):
    choices = ("color_bars","zone_plate","colored_noise","crossing_objects")
    phase,source = divmod(c.frame,c.params["hold_frames"])
    return render(choices[phase%4],c.width,c.height,c.fps,source,(c.seed+phase)%2**64)


@register(35, "luminance_transitions", "transitions", {"hold_frames":14,"full_screen":False,"allow_high_rate_flash":False},
          "FLASHING: full-screen >3 transitions/second requires explicit allow_high_rate_flash")
def luminance_transitions(c):
    if c.params["full_screen"] and c.fps/c.params["hold_frames"]>3 and not c.params["allow_high_rate_flash"]:
        raise ValueError("High-rate full-screen flashing is disabled; explicitly set allow_high_rate_flash=true")
    light = 235 if (c.frame//c.params["hold_frames"])%2 else 20
    if c.params["full_screen"]:
        return gray(light,c)
    im,d = canvas(c,(80,80,80))
    d.rectangle((c.width//4,c.height//4,c.width*3//4-1,c.height*3//4-1),fill=(light,)*3)
    return np.asarray(im)


@register(36, "dissolves_fades", "transitions", {"period_frames":24})
def dissolves_fades(c):
    period = c.params["period_frames"]
    if period < 6:
        raise ValueError("dissolves_fades period_frames must be >=6")
    phase = (c.frame%period)/period
    a = render("color_bars",c.width,c.height,c.fps,c.frame,c.seed).astype(float)
    b = render("moving_gradients",c.width,c.height,c.fps,c.frame,c.seed).astype(float)
    if phase<1/3:
        out = a*(1-phase*3)+b*phase*3
    elif phase<2/3:
        out = b*(2-phase*3)
    else:
        out = np.full_like(a,210 if phase<5/6 else 35)
    return out.clip(0,255).round().astype(np.uint8)


@register(37, "scrolling_text", "timing", {"speed":1.0,"text":"VIDEO STRESS 0123456789","scale":1})
def scrolling_text(c):
    from .font import text
    im,d = canvas(c,(18,22,28))
    for i in range(3):
        scale = c.params["scale"]*(i+1)
        length = len(c.params["text"])*6*scale
        x = c.width-int((c.t*c.params["speed"]*(i+1)*c.width/3)%(c.width+length))
        y = i*c.height//3
        text(d,(x,y),c.params["text"],scale,tuple(int(v) for v in PALETTE[i+1]))
    return np.asarray(im)


def source_identity(name,geometry,frame,seed,params):
    import hashlib
    import json
    from .provenance import code_identity
    value = {"pattern":name,"version":1,"generator":code_identity()["source_sha256"],"params":params,"seed":seed,
             "mode":list(geometry),"source_frame":frame}
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(",",":")).encode()).hexdigest()


@register(38, "frame_counter", "timing", {"scale":1})
def frame_counter(c):
    from fractions import Fraction
    from .font import text
    im,d = canvas(c,(12,24,38))
    pts = Fraction(c.frame,c.fps)
    source_id = source_identity("frame_counter",(c.width,c.height,c.fps),c.frame,c.seed,c.params)
    lines = [f"FRAME {c.frame}",f"TIME {pts.numerator}/{pts.denominator}",f"ID {source_id[:12]}"]
    for i,line in enumerate(lines):
        text(d,(2,2+i*9*c.params["scale"]),line,c.params["scale"])
    return np.asarray(im)


@register(39, "visual_audio_sync", "timing", {"interval_frames":7,"speed":1.0})
def visual_audio_sync(c):
    im,d = canvas(c,(16,20,26))
    x = (c.width/2+c.t*c.params["speed"]*c.width/3)%c.width
    y = c.height/2+np.sin(c.t*2)*c.height/4
    r = max(1,min(c.width,c.height)//10)
    event = c.frame%c.params["interval_frames"]==0
    d.ellipse((x-r,y-r,x+r,y+r),fill=(255,240,20) if event else (35,130,220))
    if event:
        d.line((0,c.height-1,c.width-1,c.height-1),fill=(255,240,20),width=2)
    return np.asarray(im)


@register(40, "machine_sync_track", "timing", {"cell_size":2}, "VSG marker with frame index, rational PTS and CRC32/triple redundancy; tiny sizes explicitly unavailable")
def machine_sync_track(c):
    from .marker import layout, mark
    x,y = c.grid
    a = rgb([32+(x+c.frame*3)%160,48+(y+c.frame*2)%120,70],c)
    spec = layout(c.width,c.height,c.params["cell_size"],fps=c.fps)
    if spec["status"] == "AVAILABLE":
        return mark(a,c.frame,c.fps,spec)
    return a


def render(name, width, height, fps, frame, seed=0, params=None):
    if type(frame) is not int or frame < 0 or type(seed) is not int or not 0 <= seed < 2**64:
        raise ValueError("Source frame must be nonnegative and seed an unsigned 64-bit integer")
    from .contract import mode
    mode({"width": width, "height": height, "fps": fps})
    p = resolve(name)
    c = Context(width, height, fps, frame, seed, parameters(p, {} if params is None else params), int(p["id"][1:]))
    a = p["render"](c)
    if a.shape != (height,width,3) or a.dtype != np.uint8:
        raise RuntimeError(f"{name} violated native RGB frame contract")
    return np.ascontiguousarray(a)
