package com.nao20010128nao.ProguardTransformFix

import com.android.build.gradle.internal.pipeline.TransformTask
import com.android.build.gradle.internal.transforms.FixedProGuardTransform
import org.gradle.api.Plugin
import org.gradle.api.Project

class PluginMain implements Plugin<Project> {

    PgtConfig config

    @Override
    void apply(Project project) {
        config=project.extensions.create('pgt',PgtConfig)
        project.gradle.taskGraph.beforeTask {task->
            if(task.project==project && task instanceof TransformTask){
                FixedProGuardTransform.injectProGuardTransform(task,config.librariesContainingClass)
            }
        }
    }
}

class PgtConfig {
    List<String> librariesContainingClass=[]

    void librariesContainingClass(String... fqcn){
        librariesContainingClass(Arrays.asList(fqcn))
    }

    void librariesContainingClass(List<String> fqcn){
        if(!librariesContainingClass){
            librariesContainingClass=[]
        }
        librariesContainingClass.addAll(fqcn)
    }
}