import os

whitelist = ['libTKDESTEP.so', 'libTKXCAF.so', 'libTKLCAF.so', 'libTKCAF.so', 'libTKCDF.so', 'libTKV3d.so', 'libTKMesh.so', 'libTKXMesh.so', 'libTKBO.so', 'libTKPrim.so', 'libTKHLR.so', 'libTKShHealing.so', 'libTKTopAlgo.so', 'libTKGeomAlgo.so', 'libTKGeomBase.so', 'libTKBRep.so', 'libTKG3d.so', 'libTKG2d.so', 'libTKMath.so', 'libTKernel.so', 'libTKDE.so', 'libTKXSBase.so', 'libTKVCAF.so', 'libTKService.so']
for abi in ['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']:
    for file in os.listdir(abi):
        if file not in whitelist:
            os.remove(abi + '/' + file)