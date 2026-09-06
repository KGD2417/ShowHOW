"""Run a ShowHow robot program on a Franka Panda in PyBullet.

    python tools/sim/guide_to_program.py guide.json -o program.json
    python tools/sim/run_pybullet.py program.json               # watch it
    python tools/sim/run_pybullet.py program.json --gif demo.gif --headless
    python tools/sim/run_pybullet.py --selfcheck

The bench is a dummy laptop: a base, a back panel, four corner screws and a
tray. The panel is bolted to the base by a constraint that is only released
once all four screws are out, so the arm cannot lift the panel early -- the
physics tells that part of the story, not a caption.

Everything loaded ships inside pybullet_data. Nothing to download.
"""
import argparse, json, math, re, time
import pybullet as p
import pybullet_data

TABLE_TOP = 0.626            # pybullet_data's table/table.urdf, measured
EE = 11                      # panda_grasptarget link
ARM = list(range(7))
FINGERS = (9, 10)
HOME = [0, -0.6, 0, -2.2, 0, 1.6, 0.79]
DOWN = p.getQuaternionFromEuler([math.pi, 0, 0])   # gripper pointing at the table
HOVER = 0.13                 # how far above a part the approach stops
ARM_FORCE = 800              # this URDF's shoulder droops under 300

# IK has to be given the joint limits explicitly. Without them PyBullet solves
# damped-least-squares in free space, hands back angles the motors then clamp,
# and the arm folds up behind itself instead of reaching the bench. Nine
# entries: the seven arm joints plus the two fingers.
LOWER = [-2.90, -1.76, -2.90, -3.07, -2.90, -0.02, -2.90, 0.00, 0.00]
UPPER = [2.90, 1.76, 2.90, -0.07, 2.90, 3.75, 2.90, 0.04, 0.04]
RANGE = [u - l for u, l in zip(UPPER, LOWER)]

GUI = True
FRAMES = []                  # captured only when --gif was asked for
RECORDING = False
CAPTION = ""                 # the step line burnt into those frames
TITLE = "ShowHow"            # top-left, the guide's own name
STEP_OF = ""                 # top-right, "STEP 3 / 7"

# Close on the laptop, not the room: the whole point of the shot is that a
# judge can see the screws come out.
SHOT = (640, 480)
VIEW = p.computeViewMatrix([1.02, -0.80, 1.24], [0.44, 0.02, 0.71], [0, 0, 1])
PROJ = p.computeProjectionMatrixFOV(45, SHOT[0] / SHOT[1], 0.05, 3)
LIGHT = [-0.6, -0.9, 1.4]


# --- scene ----------------------------------------------------------------

def box(half, rgba, mass, pos):
    return p.createMultiBody(
        mass,
        p.createCollisionShape(p.GEOM_BOX, halfExtents=half),
        p.createVisualShape(p.GEOM_BOX, halfExtents=half, rgbaColor=rgba),
        pos)


def cylinder(r, h, rgba, mass, pos):
    return p.createMultiBody(
        mass,
        p.createCollisionShape(p.GEOM_CYLINDER, radius=r, height=h),
        p.createVisualShape(p.GEOM_CYLINDER, radius=r, length=h, rgbaColor=rgba),
        pos)


class Laptop:
    """A base, a back panel screwed to it, and the four screws doing that."""

    BASE = [0.150, 0.105, 0.012]
    PANEL = [0.150, 0.105, 0.004]
    INSET = (0.130, 0.085)
    RAM = [0.040, 0.014, 0.005]
    # The back panel stands off the base by this much, which is the cavity the
    # RAM lives in. A panel flush to the base has nowhere to put a module, and
    # a step that says "out of its slot" then has no slot to mean.
    GAP = 0.014

    def __init__(self, cx, cy, screws=4):
        z = TABLE_TOP + self.BASE[2]
        self.base = box(self.BASE, [0.13, 0.14, 0.16, 1], 0, [cx, cy, z])
        self.floor = z + self.BASE[2]                    # inside, where parts sit
        self.offset = self.BASE[2] + self.GAP + self.PANEL[2]
        self.panel_z = z + self.offset
        self.panel = box(self.PANEL, [0.62, 0.64, 0.68, 1], 0.25,
                         [cx, cy, self.panel_z])
        self.panel_top = self.panel_z + self.PANEL[2]
        self.home = [cx, cy]
        self.slot = [cx - 0.055, cy + 0.030, self.floor + self.RAM[2]]
        self.ram = box(self.RAM, [0.10, 0.70, 0.30, 1], 0.03, self.slot)
        # Corners nearest the camera first, so a two-screw laptop -- which is
        # what a lot of them actually are -- puts both of them where they can
        # be seen coming out.
        corners = [(-1, -1), (1, -1), (-1, 1), (1, 1)][:max(1, screws)]
        self.screws = []
        for sx, sy in corners:
            pos = [cx + sx * self.INSET[0], cy + sy * self.INSET[1],
                   self.panel_top + 0.007]
            self.screws.append(cylinder(0.008, 0.016, [0.95, 0.72, 0.10, 1],
                                        0.02, pos))
        # What actually holds the panel down. Released in exactly one place.
        self.bolts = p.createConstraint(self.base, -1, self.panel, -1,
                                        p.JOINT_FIXED, [0, 0, 0], [0, 0, 0],
                                        [0, 0, -self.offset])
        self.out = 0

    def screw_pos(self, i):
        return list(p.getBasePositionAndOrientation(self.screws[i])[0])

    def tighten(self):
        """Bolted down again, so the last steps are checking a closed laptop."""
        if self.bolts is None:
            self.bolts = p.createConstraint(
                self.base, -1, self.panel, -1, p.JOINT_FIXED, [0, 0, 0],
                [0, 0, 0], [0, 0, -self.offset])
            self.out = 0

    def loosen(self):
        """One screw out. The panel comes free only when the last one does."""
        self.out += 1
        if self.out >= len(self.screws) and self.bolts is not None:
            p.removeConstraint(self.bolts)
            self.bolts = None


def setup(gui=True):
    global GUI
    GUI = gui
    p.connect(p.GUI if gui else p.DIRECT)
    p.setAdditionalSearchPath(pybullet_data.getDataPath())
    p.configureDebugVisualizer(p.COV_ENABLE_GUI, 0)
    p.setGravity(0, 0, -9.8)
    floor = p.loadURDF("plane.urdf")
    p.changeVisualShape(floor, -1, rgbaColor=[0.90, 0.90, 0.93, 1])
    # The arm is bolted to the bench, like the ones in a repair shop. Standing
    # it on the floor beside the table puts the panda's own shoulder inside the
    # tabletop's collision box, and the IK solution is then unreachable in a way
    # that only shows up as the arm thrashing.
    p.loadURDF("table/table.urdf", [0.5, 0, 0])
    arm = p.loadURDF("franka_panda/panda.urdf", [0, 0, TABLE_TOP], useFixedBase=True)
    for j, v in zip(ARM, HOME):
        p.resetJointState(arm, j, v)
    p.resetDebugVisualizerCamera(0.95, 55, -30, [0.46, 0.02, 0.68])
    return arm


def build_scene(objects, ops):
    """The laptop goes where the guide put it; loose parts sit beside it."""
    where = {o["name"]: o["pos"] for o in objects}
    at = (where.get("laptop") or where.get("back panel") or where.get("panel")
          or [0.50, 0.0, 0.0])
    # How many screws this laptop has is the guide's business, not a constant:
    # the expert said "two screws" or "four", and the bench has to match or the
    # step after it is a lie.
    said = next((o["say"] for o in ops if o["target"] in ("screw", "screws")), "")
    laptop = Laptop(at[0], at[1], screws=how_many(said, 4) if said else 4)
    p.loadURDF("tray/tray.urdf", [0.34, 0.27, TABLE_TOP], globalScaling=0.35)
    # The spare, waiting on the bench. Blue, so a judge can tell at a glance
    # which module went in and which one came out.
    spare = box(Laptop.RAM, [0.20, 0.35, 0.85, 1], 0.03,
                [0.56, -0.26, TABLE_TOP + Laptop.RAM[2]])
    return laptop, spare


# --- motion ---------------------------------------------------------------

def font(size):
    from PIL import ImageFont
    for name in ("segoeui.ttf", "arial.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


def snap():
    """One offscreen frame, captioned with the step's own words.

    getCameraImage and not the GUI recorder: this has to produce something a
    judge can watch on a laptop with no display attached.
    """
    from PIL import Image, ImageDraw
    w, h = SHOT
    px = p.getCameraImage(w, h, VIEW, PROJ, shadow=1, lightDirection=LIGHT,
                          renderer=p.ER_TINY_RENDERER)[2]
    img = Image.frombuffer("RGBA", (w, h), bytes(px), "raw", "RGBA", 0, 1).convert("RGB")
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, w, 34], fill=(17, 19, 24))
    d.text((14, 9), TITLE, font=font(15), fill=(232, 234, 238))
    if STEP_OF:
        big = font(15)
        tw = d.textlength(STEP_OF, font=big)
        d.text((w - tw - 14, 9), STEP_OF, font=big, fill=(255, 176, 46))
    if CAPTION:
        d.rectangle([0, h - 40, w, h], fill=(17, 19, 24))
        d.text((14, h - 29), CAPTION[:86], font=font(15), fill=(235, 237, 240))
    FRAMES.append(img)


def write_mp4(path, frames, fps=20):
    """Frames straight into the ffmpeg binary that ships with imageio-ffmpeg."""
    import subprocess
    import imageio_ffmpeg
    w, h = frames[0].size
    cmd = [imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-f", "rawvideo",
           "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-r", str(fps), "-i", "-",
           "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20", path]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for fr in frames:
        proc.stdin.write(fr.tobytes())
    proc.stdin.close()
    proc.wait()


def hold(steps=60, every=15):
    for i in range(steps):
        if not p.isConnected():
            # Somebody shut the window. That is a fine way to end a demo; a
            # stack trace across the projector is not.
            raise SystemExit("window closed")
        p.stepSimulation()
        if RECORDING and i % every == 0:
            snap()
        if GUI:
            time.sleep(1 / 240)   # headless has nobody to watch it


def move(arm, xyz, grip=0.04, settle=90):
    goal = p.calculateInverseKinematics(
        arm, EE, xyz, DOWN,
        lowerLimits=LOWER, upperLimits=UPPER, jointRanges=RANGE,
        restPoses=HOME + [grip, grip], maxNumIterations=200,
        residualThreshold=1e-4)
    for j in ARM:
        p.setJointMotorControl2(arm, j, p.POSITION_CONTROL, goal[j],
                                force=ARM_FORCE, maxVelocity=1.2)
    for f in FINGERS:
        p.setJointMotorControl2(arm, f, p.POSITION_CONTROL, grip, force=30)
    hold(settle)


def twist(arm, turns=2.5):
    """Rotate the wrist -- an unscrew, seen from across the room."""
    base = p.getJointState(arm, 6)[0]
    for k in range(10):
        # joint7 stops at +-2.9 rad, so a "turn" is as far as the wrist goes
        # before it would have to wind back -- which is what a hand does too.
        target = base + turns * (k + 1) / 10
        p.setJointMotorControl2(arm, 6, p.POSITION_CONTROL,
                                max(LOWER[6], min(UPPER[6], target)),
                                force=ARM_FORCE, maxVelocity=2.5)
        hold(14)


# ponytail: the gripper holds parts with a constraint, not with friction. Two
# fingers closing on a 6 mm screw head is a grasping research problem, and a
# screw squirting across the bench mid-pitch proves nothing about the guide.
# Swap for real contact if the simulation itself is ever the point.
GRASP = [None]


def attach(arm, body):
    """Hold the part exactly where it already is, relative to the gripper.

    Pinning both constraint frames to the origin instead teleports the part
    onto the gripper the instant it is grabbed, and a back panel that jumps
    4 mm sideways sweeps through the RAM underneath it and fires it off the
    bench. Which is what it did.
    """
    release()
    hand, horn = p.getLinkState(arm, EE)[:2]
    pos, orn = p.getBasePositionAndOrientation(body)
    inv_p, inv_o = p.invertTransform(hand, horn)
    rel_p, rel_o = p.multiplyTransforms(inv_p, inv_o, pos, orn)
    GRASP[0] = p.createConstraint(arm, EE, body, -1, p.JOINT_FIXED, [0, 0, 0],
                                  parentFramePosition=rel_p,
                                  childFramePosition=[0, 0, 0],
                                  parentFrameOrientation=rel_o)


def release():
    if GRASP[0] is not None:
        p.removeConstraint(GRASP[0])
        GRASP[0] = None


def carry(arm, body, grab, drop):
    """Pick a body up from `grab` and put it down at `drop`."""
    move(arm, [grab[0], grab[1], grab[2] + HOVER])
    move(arm, grab, grip=0.04, settle=70)
    move(arm, grab, grip=0.005, settle=40)
    attach(arm, body)
    # Carry high. A back panel dragged across the bench at grasp height plows
    # into the laptop it just came off, and the arm stalls against it.
    move(arm, [grab[0], grab[1], TRANSIT], grip=0.005)
    move(arm, [drop[0], drop[1], TRANSIT], grip=0.005, settle=170)
    move(arm, drop, grip=0.005, settle=90)
    release()
    move(arm, [drop[0], drop[1], drop[2] + HOVER])


TRANSIT = TABLE_TOP + 0.22   # cruise height between one part and the next
TRAY = [0.34, 0.27, TABLE_TOP + 0.05]
ASIDE = [0.30, -0.25, TABLE_TOP + 0.02]

COUNTS = {"one": 1, "two": 2, "three": 3, "four": 4, "both": 2, "all": 4}


def how_many(say, most):
    """"Unscrew the four screws" means four, not one."""
    for w in re.findall(r"[a-z]+|\d+", say.lower()):
        n = COUNTS.get(w) or (int(w) if w.isdigit() else 0)
        if 0 < n <= most:
            return n
    return 1


def do_screws(arm, op, laptop):
    kind = op["op"]
    for _ in range(how_many(op["say"], len(laptop.screws) - laptop.out)):
        i = laptop.out
        head = laptop.screw_pos(i)
        move(arm, [head[0], head[1], head[2] + HOVER])
        move(arm, [head[0], head[1], head[2]], grip=0.02, settle=70)
        twist(arm, turns=-2.5 if kind == "unscrew" else 2.5)
        attach(arm, laptop.screws[i])
        move(arm, [head[0], head[1], TRANSIT], grip=0.005)
        move(arm, TRAY, grip=0.005, settle=150)
        release()
        laptop.loosen()
        hold(30)


SCRAP = [0.31, 0.10, TABLE_TOP + 0.01]   # where the old module ends up


def run_op(arm, op, laptop, spare):
    x, y, z = op["pos"]
    kind, target = op["op"], op["target"]

    if target in ("screw", "screws") and kind in ("unscrew", "screw"):
        do_screws(arm, op, laptop)
        move(arm, [laptop.home[0], laptop.home[1], laptop.panel_top + HOVER])
        return

    if target in ("back panel", "panel", "laptop"):
        here = list(p.getBasePositionAndOrientation(laptop.panel)[0])
        seat = [laptop.home[0], laptop.home[1], laptop.panel_top]
        if kind == "pick":
            carry(arm, laptop.panel, [here[0], here[1], here[2] + 0.004], ASIDE)
        elif kind in ("place", "screw"):
            carry(arm, laptop.panel, [here[0], here[1], here[2] + 0.004], seat)
            if kind == "screw":
                twist(arm, turns=2.5)
                laptop.tighten()
        else:
            move(arm, [here[0], here[1], here[2] + HOVER])
            move(arm, [here[0], here[1], here[2] + 0.05])
            move(arm, [here[0], here[1], here[2] + HOVER])
        return

    if "ram" in target or "module" in target:
        # Everything about a RAM step happens at the slot: the old one comes
        # out of it, the new one goes into it, and "press until it clips" is
        # pressing on it. The bench coordinates in the program are where the
        # detector thought the part was; the slot is where it actually is.
        slot = laptop.slot
        top = [slot[0], slot[1], slot[2] + laptop.RAM[2]]
        if kind == "pick":
            here = list(p.getBasePositionAndOrientation(laptop.ram)[0])
            carry(arm, laptop.ram, [here[0], here[1], here[2] + laptop.RAM[2]],
                  SCRAP)
        elif kind == "place":
            here = list(p.getBasePositionAndOrientation(spare)[0])
            carry(arm, spare, [here[0], here[1], here[2] + laptop.RAM[2]],
                  [slot[0], slot[1], slot[2] + 0.002])
        else:                                    # push it home
            move(arm, [top[0], top[1], top[2] + HOVER])
            move(arm, top, grip=0.0, settle=80)
            move(arm, [top[0], top[1], top[2] + HOVER])
        return

    above = [x, y, z + HOVER]
    move(arm, above)
    move(arm, [x, y, z] if kind == "push" else [x, y, z + 0.05],
         grip=0.0 if kind == "push" else 0.04, settle=70)
    move(arm, above)


# --- entry points ---------------------------------------------------------

def play(prog, gui=True, gif=None, limit=0, mp4=None):
    global RECORDING, CAPTION, TITLE, STEP_OF
    arm = setup(gui=gui)
    laptop, spare = build_scene(prog["objects"], prog["ops"])
    RECORDING = bool(gif or mp4)
    TITLE = prog.get("title", "ShowHow")
    ops = prog["ops"][:limit] if limit else prog["ops"]
    hold(60)
    for n, op in enumerate(ops, 1):
        STEP_OF = f"{op['op'].upper()}   {n} / {len(ops)}"
        CAPTION = f"{op['say']}"
        print(f"{n}. {op['op'].upper():8} {op['target']:12} -- {op['say']}")
        tag = p.addUserDebugText(CAPTION, [0.05, -0.45, 1.15], [0, 0, 0], 1.0) if gui else None
        run_op(arm, op, laptop, spare)
        if tag is not None:
            p.removeUserDebugItem(tag)
    CAPTION, STEP_OF = "", "DONE"
    hold(600 if gui else 60)
    if gif and FRAMES:
        small = [f.resize((f.width // 2, f.height // 2)) for f in FRAMES]
        small[0].save(gif, save_all=True, append_images=small[1:],
                      duration=60, loop=0, optimize=True)
        print(f"{len(FRAMES)} frames -> {gif}")
    if mp4 and FRAMES:
        write_mp4(mp4, FRAMES)
        print(f"{len(FRAMES)} frames -> {mp4}")
    return arm, laptop, spare


def selfcheck():
    """The screws come out, and the panel only comes off once they have."""
    import os, subprocess, sys, tempfile
    here = os.path.dirname(os.path.abspath(__file__))
    out = os.path.join(tempfile.gettempdir(), "showhow_selfcheck.json")
    subprocess.run([sys.executable, os.path.join(here, "guide_to_program.py"),
                    os.path.join(here, "sample_guide.json"), "-o", out], check=True)
    prog = json.load(open(out, encoding="utf-8"))
    assert how_many("Unscrew the four screws on the back panel", 4) == 4
    assert how_many("Undo the two screws at the bottom", 4) == 2
    assert how_many("Undo the screw", 4) == 1
    arm, laptop, spare = play(prog, gui=False)
    assert len(laptop.screws) == 2, f"the bench built {len(laptop.screws)} screws"
    assert laptop.out == 0 and laptop.bolts is not None, "the laptop was left open"
    old = p.getBasePositionAndOrientation(laptop.ram)[0]
    new = p.getBasePositionAndOrientation(spare)[0]
    slot = laptop.slot
    assert math.dist(old[:2], SCRAP[:2]) < 0.05, f"old module not set aside: {old}"
    assert math.dist(new[:2], slot[:2]) < 0.04, f"new module not in the slot: {new}"
    assert new[2] < laptop.panel_z, f"new module is not inside the laptop: {new}"
    panel = p.getBasePositionAndOrientation(laptop.panel)[0]
    assert math.dist(panel[:2], laptop.home) < 0.02, f"panel not refitted: {panel}"
    p.disconnect()
    print("ok")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("program", nargs="?")
    ap.add_argument("--headless", action="store_true")
    ap.add_argument("--gif", help="write an animated gif of the run")
    ap.add_argument("--mp4", help="write an mp4 of the run")
    ap.add_argument("--ops", type=int, default=0, help="stop after N ops")
    ap.add_argument("--selfcheck", action="store_true")
    a = ap.parse_args()
    if a.selfcheck:
        selfcheck(); return
    prog = json.load(open(a.program, encoding="utf-8"))
    play(prog, gui=not a.headless, gif=a.gif, limit=a.ops, mp4=a.mp4)
    p.disconnect()


if __name__ == "__main__":
    main()
