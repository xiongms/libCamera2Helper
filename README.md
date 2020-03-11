# libCamera2Helper

使用Android Camera2相关Api实现视频预览与视频录制，并支持实时预览图像获取。绑定LifecycleOwner实现自动预览与停止

Step 1. Add the JitPack repository to your build file

gradle
maven
sbt
leiningen
Add it in your root build.gradle at the end of repositories:

	allprojects {
		repositories {
			...
			maven { url 'https://jitpack.io' }
		}
	}
Step 2. Add the dependency

	dependencies {
	        implementation 'com.github.xiongms:libCamera2Helper:1.0'
	}
