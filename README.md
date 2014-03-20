——————————————————————————————————————————
Copyright 2014 Srikalyan Chandrashekar. Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License. You may obtain a copy
 of the License at http://www.apache.org/licenses/LICENSE-2.0 Unless required by 
applicable law or agreed to in writing, software distributed under the License 
is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, 
either express or implied. See the License for the specific language governing 
permissions and limitations under the License. See accompanying LICENSE file.
——————————————————————————————————————————

—————————————— About the TapTime ——————————————
- A very light weight mechanism based on byte code injection to get method wall clock
 times and basic metrics like call count, lowest and max CPU times. Why not dynamic 
 proxy ?(Reflection calls overhead and requirement to rewrite the Monitored Class to 
implement and interface which is painful as you may only want to trace 1 method but 
when more methods have to tapped the interface has to be changed. Not practical at all. 

——————— How to use the utility —————————
- Include javassist.jar and TapTime.jar in the build path /class path.
- To trace methods at runtime annotate them with @Tap
 
USAGE:
	TapTime.init("package"); // This has to be the very first line(preferably in a static initializer)



——————— V1 Release Notes —————————
- If your project does some runtime validation of class metadata then this utility will cause unexpected behavior in your code.
- May not go well together with a debugger.
