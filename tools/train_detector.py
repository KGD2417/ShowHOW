"""
Fine-tune a MediaPipe object detector on screwdrivers and screw-head types.

Model Maker rather than YOLO or anything else for one reason: it writes TFLite
Model Metadata into the export, and MediaPipe's ObjectDetector refuses a model
without it. The output drops straight over files/models/object_detector.tflite
on the phone with no app change at all.

MOBILENET_MULTI_AVG rather than a bigger backbone: it is what already runs on
the phone's NPU at video rate, and a viewfinder overlay that halves the frame
rate is a worse product than one that is slightly less accurate.
"""
import os
import sys
import glob

DATA = os.path.expanduser("~/sd_merged")
OUT = os.path.expanduser("~/sd_out")


def enable_gpu():
    """Point TF at the CUDA libraries pip installed under nvidia/*/lib.

    XLA also needs libdevice, which lives in the separate cuda_nvcc wheel and
    is not on any library path TF looks at. Without it the first op that gets
    JIT-clustered dies with "JIT compilation failed. [Op:Sqrt]" -- which reads
    like a GPU fault and is really a missing file. Auto-clustering is turned
    off as well, so a libdevice that is still not found costs speed rather than
    the whole run.
    """
    try:
        import nvidia
        base = os.path.dirname(nvidia.__file__)
        libs = [os.path.join(r, d) for r, ds, _ in os.walk(base) for d in ds if d == "lib"]
        if libs:
            os.environ["LD_LIBRARY_PATH"] = ":".join(libs) + ":" + os.environ.get("LD_LIBRARY_PATH", "")
            print("CUDA libs:", len(libs), "dirs")
        nvcc = os.path.join(base, "cuda_nvcc")
        if os.path.isdir(os.path.join(nvcc, "nvvm", "libdevice")):
            os.environ["XLA_FLAGS"] = f"--xla_gpu_cuda_data_dir={nvcc}"
            print("libdevice:", nvcc)
        else:
            print("no libdevice found; XLA clustering stays off")
    except ImportError:
        print("no nvidia pip libs; CPU it is")
    # Belt and braces: never let XLA decide to JIT something on its own.
    os.environ["TF_XLA_FLAGS"] = "--tf_xla_auto_jit=0"


def main():
    enable_gpu()
    import tensorflow as tf
    gpus = tf.config.list_physical_devices("GPU")
    print("TF", tf.__version__, "| GPUs:", len(gpus))
    for g in gpus:
        try:
            tf.config.experimental.set_memory_growth(g, True)
        except Exception:
            pass

    from mediapipe_model_maker import object_detector

    train_dir = os.path.join(DATA, "train")
    val_dir = os.path.join(DATA, "valid")
    for d in (train_dir, val_dir):
        if not os.path.isfile(os.path.join(d, "labels.json")):
            print("missing COCO json in", d)
            sys.exit(2)

    train_data = object_detector.Dataset.from_coco_folder(train_dir, cache_dir="/tmp/od_tr")
    val_data = object_detector.Dataset.from_coco_folder(val_dir, cache_dir="/tmp/od_va")
    print("train", train_data.size, "valid", val_data.size)

    hparams = object_detector.HParams(
        # 6 GB laptop card. 8 fits alongside the 256x256 input without the
        # allocator thrashing; on CPU it just means slower epochs, not failure.
        batch_size=int(os.environ.get("BS", "8")),
        learning_rate=0.3,
        epochs=int(os.environ.get("EPOCHS", "30")),
        export_dir=OUT,
    )
    options = object_detector.ObjectDetectorOptions(
        supported_model=object_detector.SupportedModels.MOBILENET_MULTI_AVG,
        hparams=hparams,
    )

    print("\n=== training ===", flush=True)
    model = object_detector.ObjectDetector.create(
        train_data=train_data, validation_data=val_data, options=options
    )

    print("\n=== evaluating ===", flush=True)
    loss, metrics = model.evaluate(val_data, batch_size=4)
    print("val loss:", loss)
    print("metrics:", metrics)

    print("\n=== exporting ===", flush=True)
    model.export_model("screwdriver.tflite")
    made = os.path.join(OUT, "screwdriver.tflite")
    print("EXPORTED", made, os.path.getsize(made), "bytes")


if __name__ == "__main__":
    main()
