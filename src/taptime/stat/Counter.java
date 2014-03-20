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
package taptime.stat;

import java.text.DecimalFormat;

/**
 * Simple method stat container object. No getter/setter for field access.
 * @author srikalyc
 */
public class Counter implements Comparable<Counter> {

    public static final DecimalFormat format = new DecimalFormat("#.#####");

    public String name;
    public long start;// ns representing method start timestamp
    public long count;// # of times the method is invoked.
    public long min = Long.MAX_VALUE;// least wall clock time in ns.
    public long max = Long.MIN_VALUE;// max wall clock time in ns.
    public long aggregateTime;// nanoSecs(total of all wall clock times)

    public Counter(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        StringBuilder buff = new StringBuilder();
        buff.append("Method  :");
        buff.append(name);
        buff.append("\n");
        buff.append("        [Call count:");
        buff.append(count);
        buff.append("]");
        buff.append("\n");
        buff.append("        [Avg time  :");
        buff.append(format.format(aggregateTime / (count* 1000000.0)));
        buff.append("ms]");
        buff.append("\n");
        buff.append("        [Min clocked  :");
        buff.append(format.format(min));
        buff.append("ns]");
        buff.append("\n");
        buff.append("        [Max clocked  :");
        buff.append(format.format(max));
        buff.append("ns]");
        buff.append("\n");
        buff.append("        [Total time:");
        buff.append(format.format(aggregateTime / 1000000.0));
        buff.append("ms]");
        buff.append("\n");
        return buff.toString();
    }

    @Override
    /**
     * TODO: Possible overflow which causes incorrect results.
     */
    public int compareTo(Counter o) {
        return (int)(o.count - count);
    }

}
