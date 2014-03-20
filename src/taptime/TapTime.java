/**
 * Copyright 2014 Srikalyan Chandrashekar. Licensed under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0 
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR 
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the 
 * specific language governing permissions and limitations under the License. 
 * See accompanying LICENSE file.
 */
package taptime;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import taptime.annotations.Tap;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.NotFoundException;
import triemap.ByteTrieMap;

/**
 * All enclosing classes of monitored methods are under surveillance of this facility.
 * Methods are tracked across several objects of same type. NOTE: We are interested
 * in collecting range of famous to infamous method sequences.
 *
 * @author srikalyc
 */
public class TapTime {

    private static final Map<CtClass, List<String>> classCounterFields = new HashMap<>();

    protected static final Map<String, CtClass> modifiedMonitoredCtClasses = new HashMap<>();

    /**
     * The following is static as it has to be static to be tracked across
     * method calls (heterogenous stacks i.e method calls of different classes).
     * Atmost 255 methods can be tapped( reason is we want to save space and use
     * byte as method id).Would love to use TreeMap(instead of HashMap) as it is
     * faster for smaller sets but CtMethod is not Comparable.
     */
    private static final Map<CtMethod, Byte> methodIdTable = new HashMap<>();
    /**
     * This is used later to assign prime Ids to methods such that lower primes
     * get assigned to most accessed methods and vice versa. This is not static
     * variable as state information is independent of across method calls.
     */
    private static final Map<String, CtMethod> methodCtrTable = new HashMap<>();
    /**
     * 256 is the max number of methods. This is just reverse of methodIdTable.
     */
    private static final CtMethod[] methodsIndexedByIds = new CtMethod[256];
    private static final AtomicInteger idGenerator = new AtomicInteger(0);

    // Set to false after initialization of MAX_NUM_CALLS_TRACKED.
    private static boolean canSetMaxNumCalls = true;
    private static int MAX_NUM_CALLS_TRACKED = 200000000;

    // Represents a sequence of methods called/returned. Consumes MAX space(about 200MB) in heap.
    private static byte[] methodsInCallSequence = null;
    // Function Call = 1 and Function Return = 0.
    private static BitSet methodCallSequence = null;
    // Index into methodsInCallSequence and methodCallSequence.
    private static int index = 0;

    private static final ByteTrieMap callSeqCounts = new ByteTrieMap();

//    private static final TapClassLoader tapClassLoader = (TapClassLoader)Thread.currentThread().getContextClassLoader();

    private static final StringBuilder whiteSpaceBuf = new StringBuilder(512);// Enough for 256 deep call stack.

    static {
        // Create 8x64=512 whitespaces.
        for (int i = 0; i < 64; i++) {
            whiteSpaceBuf.append("        ");
        }
    }

    private static String generateBefore(String counterVaName) {
        StringBuilder buff = new StringBuilder("{");
        buff.append(counterVaName);
        buff.append(".count++;");
        buff.append(counterVaName);
        buff.append(".start = System.nanoTime();}");
        return buff.toString();
    }

    private static String generateAfter(String counterVaName) {
        StringBuilder buff = new StringBuilder("{");
        buff.append("long tmp = (System.nanoTime() - ");
        buff.append(counterVaName);
        buff.append(".start);if(tmp <= ");
        buff.append(counterVaName);
        buff.append(".min) {");
        buff.append(counterVaName);
        buff.append(".min = tmp;}");
        buff.append("if(tmp > ");
        buff.append(counterVaName);
        buff.append(".max) {");
        buff.append(counterVaName);
        buff.append(".max = tmp;}");
        buff.append(counterVaName);
        buff.append(".aggregateTime += tmp;}");
        return buff.toString();
    }

    private static String generateCallStart(CtMethod meth) {
        StringBuilder buff = new StringBuilder("{");
        buff.append("TapTime.callStart((byte)");
        buff.append(methodIdTable.get(meth));
        buff.append(");}");
        return buff.toString();
    }

    private static String generateCallEnd(CtMethod meth) {
        StringBuilder buff = new StringBuilder("{");
        buff.append("TapTime.callEnd((byte)");
        buff.append(methodIdTable.get(meth));
        buff.append(");}");
        return buff.toString();
    }

    public static void callStart(byte id) {
        methodsInCallSequence[index] = id;
        methodCallSequence.set(index++);
    }

    public static void callEnd(byte id) {
        methodsInCallSequence[index] = id;
        index++;// No need to se bit at index in methodCallSequence to 0(as default is 0 anyways)
    }

    /**
     * Call this before any other operation is done (otherwise this method has
     * no effect).
     *
     * @param num
     */
    public static void setMaxNumCallsTracked(int num) {
        if (canSetMaxNumCalls) {
            MAX_NUM_CALLS_TRACKED = num;
        }
    }

    /**
     * Scans all classes accessible from the context class loader which belong
     * to the given package and subpackages.
     *
     * @param packageName The base package
     * @return The classes
     */
    private static String[] getClasses(String packageName) {
        // This is set to our TapClassLoader in static block.
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        assert classLoader != null;
        String path = packageName.replace('.', '/');
        Enumeration<URL> resources = null;
        try {
            resources = classLoader.getResources(path);
        } catch (IOException ex) {
            Logger.getLogger(TapTime.class.getName()).log(Level.SEVERE, null, ex);
        }
        List<File> dirs = new ArrayList<>();
        while (resources != null && resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            dirs.add(new File(resource.getFile()));
        }
        List<String> classes = new ArrayList<>();
        for (File directory : dirs) {
            classes.addAll(findClasses(directory, packageName));
        }
        return classes.toArray(new String[classes.size()]);
    }

    /**
     * Recursive method used to find all classes in a given directory and
     * subdirs.
     *
     * @param directory The base directory
     * @param packageName The package name for classes found inside the base
     * directory
     * @return The fq class names.
     */
    private static List<String> findClasses(File directory, String packageName) {
        List<String> classes = new ArrayList<>();
        if (!directory.exists()) {
            return classes;
        }
        File[] files = directory.listFiles();
        for (File file : files) {
            if (file.isDirectory()) {
                assert !file.getName().contains(".");
                classes.addAll(findClasses(file, packageName + "." + file.getName()));
            } else if (file.getName().endsWith(".class")) {
                classes.add(packageName + '.' + file.getName().substring(0, file.getName().length() - 6));
            }
        }
        return classes;
    }

    /**
     * Injects byte code as needed into the "Monitored class".
     *
     * @param pkg
     */
    public static void init(String pkg) {
        
        if (canSetMaxNumCalls) {// Means init() was called the first time.
            methodsInCallSequence = new byte[MAX_NUM_CALLS_TRACKED];
            methodCallSequence = new BitSet(MAX_NUM_CALLS_TRACKED);
        }
        canSetMaxNumCalls = false;
        try {
            ClassPool pool = ClassPool.getDefault();
            pool.importPackage("taptime.stat.Counter");
            pool.importPackage("taptime.TapTime");
            String[] classes = getClasses(pkg);
            for (String klass : classes) {
                CtClass modifiedMonitoredClass = pool.get(klass);
                if (modifiedMonitoredCtClasses.containsKey(klass)) {
                    return;// This class has already been modified.
                }
                if (!classCounterFields.containsKey(modifiedMonitoredClass)) {
                    classCounterFields.put(modifiedMonitoredClass, new ArrayList<String>());
                }
                modifiedMonitoredCtClasses.put(klass, modifiedMonitoredClass);
                for (CtMethod meth : modifiedMonitoredClass.getDeclaredMethods()) {
                    if (meth.hasAnnotation(Tap.class)) {
                        generateID(meth);
                        String counterVarName = meth.getName() + "_counter";
                        modifiedMonitoredClass.addField(CtField.make("public static final Counter "
                                + counterVarName + " = new Counter(\"" + meth.getLongName()
                                + "\");", modifiedMonitoredClass));
                        methodCtrTable.put(counterVarName, meth);
                        /**
                         * Each "Tapped method" is prepended with "Timing start
                         * code" + "AR entry code" appended with "AR exit code"
                         * + "Timing end code"
                         */
                        meth.insertBefore(generateBefore(counterVarName) + generateCallStart(meth));
                        meth.insertAfter(generateCallEnd(meth) + generateAfter(counterVarName));
                        classCounterFields.get(modifiedMonitoredClass).add(counterVarName);
                    }
                }
                // Modify the bytecode and persist for JVM to pick it up. 
                URL url = modifiedMonitoredClass.getURL();
                String classNameWithPackagePath = modifiedMonitoredClass.getName().replaceAll("\\.", File.separator) + ".class";
                modifiedMonitoredClass.writeFile(url.getPath().substring(0,url.getPath().lastIndexOf(classNameWithPackagePath)));
            }
        } catch (NotFoundException | CannotCompileException | IOException ex) {
            Logger.getLogger(TapTime.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * Unique Id per Class/method.
     *
     * @param meth
     */
    private static void generateID(CtMethod meth) {
        byte id = (byte) (idGenerator.getAndIncrement());
        methodIdTable.put(meth, id);
        methodsIndexedByIds[id] = meth;
    }

    /**
     * Call to see the method's individual statistics(not correlative to other
     * method calls from within).
     */
    public static void printStats() {

        for (CtClass klass : classCounterFields.keySet()) {
            List<String> counterFields = classCounterFields.get(klass);
            for (String counterFieldName : counterFields) {
                try {
                    System.out.println(Class.forName(klass.getName()).getDeclaredField(counterFieldName).get(null));
                } catch (ClassNotFoundException | NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException ex) {
                    Logger.getLogger(TapTime.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        }
        generateCommonCallSequences(false);
    }

    /**
     * All the methods annotated with @Tap.
     */
    public static void printMethodsTracked() {
        System.out.println("METHODS tracked : ");
        for (CtMethod meth : methodIdTable.keySet()) {
            System.out.print(meth.getLongName() + ",");
        }
        System.out.println("");
    }

    /**
     * Ex: prints like following CALL STACK(SIMPLE) 0(1) 1(1) 1(0) 2(1) 3(1)
     * 3(0) 3(1) 3(0) 2(0) 1(1) 1(0) 0(0)
     *
     * n(m) - where n = methodId and m = 0/1 where 1 = entered into method, 0 =
     * left from method.
     *
     */
    public static void printCallStackSimple() {
        System.out.println("CALL STACK(SIMPLE)");
        StringBuilder buf = new StringBuilder(100);
        for (int i = 0; i < index; i++) {
            buf.append(methodsInCallSequence[i]);
            buf.append("(");
            buf.append(methodCallSequence.get(i) ? 1 : 0);
            buf.append(")  ");
            System.out.print(buf);
            buf.delete(0, 100);
        }
        System.out.println("");
    }

    /**
     * Self explanatory.
     */
    public static void printCallStackHierarchy() {
        System.out.println("");
        System.out.println("CALL STACK(HIERARCHY)");
        StringBuilder buf = new StringBuilder(100);
        int numSpaces = 1;
        for (int i = 0; i < index; i++) {
            buf.append(genSpace(numSpaces));
            if (methodCallSequence.get(i)) {
                buf.append(methodsIndexedByIds[methodsInCallSequence[i]].getName());
                buf.append("\n");
                System.out.print(buf);
            }
            buf.delete(0, 100);
            if (methodCallSequence.get(i)) {
                numSpaces += 2;
            } else {
                numSpaces -= 2;
            }
        }
        System.out.println("");
    }

    private static String genSpace(int num) {
        return whiteSpaceBuf.substring(0, num);
    }

    /**
     * Generates counts for different sequence of methods in the call stack.
     *
     * @param regen (If regen is true then the data is recalculated).
     */
    private static void generateCommonCallSequences(boolean regen) {
        System.out.println("------------ COMMON CALL SEQUENCES(SORTED) ------------");
        if (!regen && callSeqCounts.size() > 0) {
            return;
        }
        // Push methodId to the stack when rolling and pop when unrolling.
        Stack<Byte> prefixCallSeq = new Stack<>();

        // The start and end indices of calls to be counted as callSequence.
        int stackStartIndex = 0;
        int stackEndIndex = 0;

        byte prefix = -1;// MethodId which is precursor to methods in call sequence[StartIndex, EndIndex]
        boolean countSeq = false;// When true we count the call sequence.
        for (int i = 0; i < index; i++) {
            if (methodCallSequence.get(i)) {// STACK ROLLING.
                prefixCallSeq.push(methodsInCallSequence[i]);
                // Reset the seqCounted(because you want to start again).
                if (countSeq) {
                    // If there is prefix include that as well.
                    if (prefix > -1) {
                        callSeqCounts.incAll(methodsInCallSequence, prefix, stackStartIndex, stackEndIndex, 1);
                    } else {// else just the methods between[startIndex, endIndex]
                        callSeqCounts.incAll(methodsInCallSequence, stackStartIndex, stackEndIndex, 1);
                    }
                    // size-1 th element is the most recently added element and hence is 
                    //already available through stackStartIndex so the prefix is size-2 nd element.
                    if (prefixCallSeq.size() > 1) {
                        prefix = prefixCallSeq.get(prefixCallSeq.size() - 2);
                    } else {// No prefix need to be included.
                        prefix = -1;
                    }
                    stackStartIndex = i;
                    countSeq = false;
                }
            } else {// STACK UNROLLING.
                if (!countSeq) {
                    countSeq = true;
                    stackEndIndex = i;
                }
                prefixCallSeq.pop();
            }
        }
        // After coming out of loop we may have missed out the last sequence.
        if (prefix > -1) {
            callSeqCounts.incAll(methodsInCallSequence, prefix, stackStartIndex, stackEndIndex, 1);
        } else {
            callSeqCounts.incAll(methodsInCallSequence, stackStartIndex, stackEndIndex, 1);
        }

        Map<List<Byte>, Integer> mapOfCounts = callSeqCounts.getKeyValueEntries();
        Map<Integer, List<List<Byte>>> countsAndSequences = new TreeMap<>();
        // Convert mapOfCounts to countsAndSequences which is just reverse map but sorted by counts.
        // Yes we are sorting by counts in the below section.
        for (Map.Entry<List<Byte>, Integer> entry:mapOfCounts.entrySet()) {
            if (countsAndSequences.containsKey(entry.getValue())) {
                countsAndSequences.get(entry.getValue()).add(entry.getKey());
            } else {
                List<List<Byte>> list = new ArrayList<>();
                list.add(entry.getKey());
                countsAndSequences.put(entry.getValue(), list);
            }
        }
        //TODO: Uncomment the following for diagnostics.
        //callSeqCounts.printKeyValueEntries();
        // Print out the Call sequence -> Count.
        for (Integer count: countsAndSequences.keySet()) {
            for (List<Byte> key : countsAndSequences.get(count)) {
                for (byte partialKey : key) {
                    System.out.print(methodsIndexedByIds[partialKey].getName() + "->");
                }
                System.out.println(count);
            }
        }
    }

}
