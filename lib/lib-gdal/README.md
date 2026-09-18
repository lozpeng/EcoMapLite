# [GDAL4Android](https://github.com/kikitte/GDAL4Android.git)

This project builds GDAL into an [Android Archive(AAR)](https://developer.android.com/studio/projects/android-library) file. So you can use GDAL's functionality in your Android App.

Version Info: GDAL 3.7.0, PROJ 9.2.1, SQLITE 3.42.0, EXPAT 2.5.0(used for kml support)

[DOWNLOAD AAR file](https://github.com/kikitte/GDAL4Android/releases)


### 换行符错误 
root@e05adbff148f:~/GDAL4Android# ./gradlew gdal:clean
/usr/bin/env: ‘sh\r’: No such file or directory
# 方法一：使用 sed 删除 \r
sed -i 's/\r$//' gradlew
# 批量转换当前目录下所有 .sh 文件（谨慎使用，先确认范围）
find . -name "*.sh" -exec sed -i 's/\r$//' {} \;
# 查找文件
find . -name "build_cpp.sh"

# 转换换行符
sed -i 's/\r$//' gdal/build_cpp.sh

find . -type f \( -name "*.sh" -o -name "gradlew" \) -exec sed -i 's/\r$//' {} \;


### Build with Docker(Recommend)

This project provides a Dockerfile which you can build a docker image which is suitable to build GDAL4Android without environment problems. Below is some examples about how you can build GDAL4Android with docker.

To build 4kb page size app(Default):

```bash
# Note: don't forgot to install docker.

cd <your-workspace-path>
git clone https://github.com/kikitte/GDAL4Android.git
cd GDAL4Android

# this step produces an image named gdal4android_builder_img
docker build -t gdal4android_builder_img - < docker/Dockerfile

# this step runs a container so you can build GDAL4Android within it.
docker run -it --name gdal4android_builder -v .:/root/GDAL4Android gdal4android_builder_img

# Note: You are now in the container environment, /root/GDAL4Android is the project root directory in the container.

# override the default FindJNI.cmake, 
cp /root/GDAL4Android/docker/cmake_modules/FindJNI.cmake /usr/share/cmake-3.22/Modules/FindJNI.cmake

# change working direction to the project root direcory
cd /root/GDAL4Android

# clean project first
./gradlew gdal:clean
# build gdal aar, the output aar file is in: GDAL4Android/gdal/build/outputs/aar/gdal-release.aar
./gradlew gdal:assembleRelease
# build gdaltest apk, the output apk fiel is in: GDAL4Android/gdaltest/build/outputs/apk/debug/gdaltest-debug.apk
./gradlew gdaltest:assembleDebug
```



To build 16kb page size app:

> In docker/Dockerfile_16kb, NDK r28 is used in order to compile 16 KB-aligned by  default, so no any other extra flags needed.

```bash
# Note: don't forgot to install docker.

cd <your-workspace-path>
git clone https://github.com/kikitte/GDAL4Android.git
cd GDAL4Android

# this step produces an image named gdal4android_builder_img
docker build -t gdal4android_builder_img_16kb - < docker/Dockerfile_16kb

# this step runs a container so you can build GDAL4Android within it.
docker run -it --name gdal4android_builder_16kb -v .:/root/GDAL4Android gdal4android_builder_img_16kb

# Note: You are now in the container environment, /root/GDAL4Android is the project root directory in the container.

# override the default FindJNI.cmake, 
cp /root/GDAL4Android/docker/cmake_modules/FindJNI.cmake /usr/share/cmake-3.22/Modules/FindJNI.cmake


# change working direction to the project root direcory
cd /root/GDAL4Android
# configure ndk directory
echo "ndk.dir=/root/android_sdk/ndk/28.1.13356709" > local.properties

# clean project first
./gradlew gdal:clean
# build gdal aar, the output aar file is in: GDAL4Android/gdal/build/outputs/aar/gdal-release.aar
./gradlew gdal:assembleRelease
# build gdaltest apk, the output apk fiel is in: GDAL4Android/gdaltest/build/outputs/apk/debug/gdaltest-debug.apk
./gradlew gdaltest:assembleDebug
```



### Build on local machine

- Linux

  bash

  some utilities: getconf & make & cmake & libtool & ant & ...

  swig: for building gdal java bindings.

- Android Studio 2022.2 or newer

  with latest ndk installed, r25c or newer

  use Android Studio default JDK as Gradle JDK (specified in Gradle settings)

You may encounter problems caused by development environment, if something is missing, just install it.

How to build?

```bash
cd <GDAL4Android root directory>

# clean project first
./gradlew gdal:clean
# build gdal aar, the output aar file is in: GDAL4Android/gdal/build/outputs/aar/gdal-release.aar
./gradlew gdal:assembleRelease
# build gdaltest apk, the output apk fiel is in: GDAL4Android/gdaltest/build/outputs/apk/debug/gdaltest-debug.apk
./gradlew gdaltest:assembleDebug
```

### Credit

https://github.com/OSGeo/gdal/blob/master/.github/workflows/android_cmake/start.sh

https://github.com/paamand/GDAL4Android
