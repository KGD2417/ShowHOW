# Empty on purpose. minifyEnabled is false in both build types because R8
# stack-walks to find the caller when MediaPipe loads its native libs, and
# renaming breaks that. Do not turn it on before the demo.
