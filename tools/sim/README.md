# Guide -> robot program -> simulation

Turns a finished ShowHow guide into something an arm can execute, and runs it.

    pip install pybullet pillow
    python tools/sim/guide_to_program.py tools/sim/sample_guide.json -o program.json
    python tools/sim/run_pybullet.py program.json                     # watch it
    python tools/sim/run_pybullet.py program.json --headless --gif demo.gif

Self-checks, both fast, neither needs a display:

    python tools/sim/guide_to_program.py --selfcheck   # text -> verb + part
    python tools/sim/run_pybullet.py --selfcheck       # the arm reaches them

## Getting a real guide off the phone

The app has no `INTERNET` permission and never will, so the guide travels down
a cable. Guides live in internal storage, which on a debug build means
`run-as`:

    adb shell run-as com.showhow ls files/guides
    adb exec-out run-as com.showhow cat files/guides/<id>/guide.json > guide.json

That one file is all the simulator reads -- not the take, not the photos. It
is also small enough to paste into a chat window if the cable is the thing
that fails on demo day.

## The bench

A dummy laptop, built from primitives so there is nothing to download: a black
base with a cavity, a silver back panel standing off it, gold corner screws, a
parts tray, the green module already in the slot and a blue spare on the
bench.

Two things are true because of the physics and not because of a caption:

  * The panel is held down by a constraint released in exactly one place --
    when the last screw lands in the tray. The arm cannot lift it early.
  * The RAM steps all happen at the slot inside the base. The green one comes
    out of it, the blue one goes into it, and "press until the clips snap"
    presses on it. Two colours so a judge can see which went where.

**The bench is built from the guide, not from a constant.** "Undo the two
screws at the bottom of the back panel" builds a laptop with two screws, at
the two corners nearest the camera, and the panel comes free when the second
one lands in the tray. A guide that says four gets four. The count is read out
of the expert's own words (`how_many`), because a bench that does not match
what the step says makes the step after it a lie.

## Running it in front of judges

Once, the day before, on the machine you will present from:

    pip install pybullet pillow
    python tools/sim/run_pybullet.py --selfcheck     # ~40 s, prints "ok"

That self-check is the dress rehearsal: it asserts four screws came out and
the panel only came free afterwards. If it prints `ok`, the demo works.

Live, two commands, in this order -- the first one is the point, so let them
read it:

    python tools/sim/guide_to_program.py guides/<id>/guide.json -o program.json
    python tools/sim/run_pybullet.py program.json

The first prints `7 ops -> program.json`. Open it on screen for five seconds:
that file is the guide a person recorded on a phone, turned into verbs with
coordinates. Then run the second and let the arm do it.

The full run is about ninety seconds. If the slot is tight, `--ops 2` stops
after the unscrew and the panel lift, which is the part that lands.

**Always have the fallback ready.** Render it before you travel and keep it
open in a browser tab:

    python tools/sim/run_pybullet.py program.json --headless --gif demo.gif

A GIF cannot fail to launch on a projector, and a judge cannot tell it from
the live window.

## What the translation is, exactly

A keyword table over the coached English instruction, into five verbs --
`unscrew`, `screw`, `pick`, `place`, `push` -- plus `inspect`, which is where
anything unreadable lands. `inspect` moves the arm to look at the part and
touch nothing, because a guessed manipulation is a robot doing something the
expert never did, and nobody watching the video could tell the difference.
The same rule the app follows everywhere else.

Asides (`"aside": true`) are skipped. A step that names no part inherits the
previous step's target, which is what "press it down until it clicks" means.

## The 2D -> 3D bit

The phone has one camera and no depth. The one thing that is actually true of
a workbench is that the parts are on it, so `box_to_world` maps a normalized
detection box (`ai/ObjectDetectSource.DetectionBox`) onto the table plane:
image y becomes depth, image x becomes lateral. `TABLE` at the top of
`guide_to_program.py` is the calibration -- four numbers, set them to whatever
the camera actually framed.

A step with no box falls back to a fixed bench layout (`BENCH`), so a guide
replays identically every time, which is what a demo needs. `program.json`
records which of the two placed each object, under `"source"`.

Boxes reach this script through an optional `"boxes"` list on a step; the app
does not write one yet (see `sample_guide.json` step 0 for the shape).

## MuJoCo

`program.json` is simulator-agnostic on purpose. PyBullet is what runs here
because the arm, the table and an IK solver all ship inside `pybullet_data` --
nothing to model, nothing to download. MuJoCo needs an MJCF scene and its own
IK on top; if it has to be MuJoCo, start from menagerie's Panda and write a
second runner against the same `ops` list.
