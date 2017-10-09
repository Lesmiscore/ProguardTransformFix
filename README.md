# ProguardTransformFix
An ugly way to avoid stack size error and using `-dontoptimize` option.   
    
Use at your own risk, or it break your build.     
There's no grantee to use this.
     
## Usage
Add following to root `build.gradle` file.

```groovy
buildscript {
    repositories {
        jcenter()
        // ...
        maven { url 'https://jitpack.io' }
    }
    dependencies {
        // change after last colon to the commit you want
        classpath 'com.github.nao20010128nao:ProguardTransformFix:f0fb66b'
    }
}
```

Then, append following to root `build.gradle` file.

```groovy
import com.android.build.gradle.internal.transforms.*
import com.android.build.gradle.internal.pipeline.*

gradle.taskGraph.beforeTask { task ->
    if(task instanceof TransformTask){
        FixedProGuardTransform.injectProGuardTransform(task)
    }
}
```

