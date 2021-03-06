/*
 * The MIT License
 *
 * Copyright 2017 Intuit Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.intuit.karate;

import com.intuit.karate.cucumber.FeatureWrapper;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jdk.nashorn.api.scripting.ScriptObjectMirror;
import net.minidev.json.JSONObject;
import net.minidev.json.JSONStyle;
import net.minidev.json.JSONValue;
import net.minidev.json.reader.JsonWriter;
import net.minidev.json.reader.JsonWriterI;
import org.yaml.snakeyaml.Yaml;

/**
 *
 * @author pthomas3
 */
public class JsonUtils {

    private JsonUtils() {
        // only static methods
    }

    private static class NashornObjectJsonWriter implements JsonWriterI<ScriptObjectMirror> {

        @Override
        public <E extends ScriptObjectMirror> void writeJSONString(E value, Appendable out, JSONStyle compression) throws IOException {
            if (value.isArray()) {
                Object[] array = value.values().toArray();
                JsonWriter.arrayWriter.writeJSONString(array, out, compression);
            } else if (value.isFunction()) {
                JsonWriter.toStringWriter.writeJSONString("\"#function\"", out, compression);
            } else { // JSON
                JsonWriter.JSONMapWriter.writeJSONString(value, out, compression);
            }
        }

    }
    
    private static class FeatureWrapperJsonWriter implements JsonWriterI<FeatureWrapper> {

        @Override
        public <E extends FeatureWrapper> void writeJSONString(E value, Appendable out, JSONStyle compression) throws IOException {
            JsonWriter.toStringWriter.writeJSONString("\"#feature\"", out, compression);
        }
        
    }

    static { // prevent things like the karate script bridge getting serialized (especially in the javafx ui)
        JSONValue.registerWriter(ScriptObjectMirror.class, new NashornObjectJsonWriter());
        JSONValue.registerWriter(FeatureWrapper.class, new FeatureWrapperJsonWriter());
    }

    public static DocumentContext toJsonDoc(String raw) {
        return JsonPath.parse(raw);
    }

    public static String toStrictJsonString(String raw) {
        DocumentContext dc = toJsonDoc(raw);
        return dc.jsonString();
    }

    public static String toJson(Object o) {
        return JSONValue.toJSONString(o);
    }

    public static DocumentContext toJsonDoc(Object o) {
        return toJsonDoc(toJson(o));
    }

    // could have used generics, but this is only going to be called from js / karate
    public static Object fromJson(String s, String className) {
        try {
            Class clazz = Class.forName(className);
            return JSONValue.parse(s, clazz);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String toPrettyJsonString(DocumentContext doc) {
        Object o = doc.read("$");
        StringBuilder sb = new StringBuilder();
        // anti recursion / back-references
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap());
        recursePretty(o, sb, 0, seen);
        sb.append('\n');
        return sb.toString();
    }

    private static void pad(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append(' ').append(' ');
        }
    }
    
    private static void ref(StringBuilder sb, Object o) {
        sb.append("\"#ref:").append(o.getClass().getName()).append('"');
    }

    private static void recursePretty(Object o, StringBuilder sb, int depth, Set<Object> seen) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof Map) {            
            if (seen.add(o)) {
                sb.append('{').append('\n');
                Map<String, Object> map = (Map<String, Object>) o;
                Iterator<Map.Entry<String, Object>> iterator = map.entrySet().iterator();
                while(iterator.hasNext()) {
                    Map.Entry<String, Object> entry = iterator.next();
                    String key = entry.getKey();
                    pad(sb, depth + 1);
                    sb.append('"').append(JSONObject.escape(key)).append('"');
                    sb.append(':').append(' ');
                    recursePretty(entry.getValue(), sb, depth + 1, seen);
                    if (iterator.hasNext()) {
                        sb.append(',');
                    }
                    sb.append('\n');
                }      
                pad(sb, depth);
                sb.append('}');                
            } else {
                ref(sb, o);
            }
        } else if (o instanceof List) {
            List list = (List) o;
            Iterator iterator = list.iterator();            
            if (seen.add(o)) {
                sb.append('[').append('\n');
                while (iterator.hasNext()) {
                    Object child = iterator.next();
                    pad(sb, depth + 1);
                    recursePretty(child, sb, depth + 1, seen);
                    if (iterator.hasNext()) {
                        sb.append(',');
                    }
                    sb.append('\n');
                }
                pad(sb, depth);
                sb.append(']');                
            } else {
                ref(sb, o);
            }
        } else if (o instanceof String) {
            String value = (String) o;
            sb.append('"').append(JSONObject.escape(value)).append('"');
        } else {
            sb.append(o);
        }
    }

    public static StringUtils.Pair getParentAndLeafPath(String path) {
        int pos = path.lastIndexOf('.');
        int temp = path.lastIndexOf("['");
        if (temp != -1 && temp > pos) {
            pos = temp - 1;
        }
        String right = path.substring(pos + 1);
        if (right.startsWith("[")) {
            pos = pos + 1;
        }
        String left = path.substring(0, pos == -1 ? 0 : pos);
        return StringUtils.pair(left, right);
    }

    public static void removeValueByPath(DocumentContext doc, String path) {
        setValueByPath(doc, path, null, true);
    }

    public static void setValueByPath(DocumentContext doc, String path, Object value) {
        setValueByPath(doc, path, value, false);
    }

    public static void setValueByPath(DocumentContext doc, String path, Object value, boolean remove) {
        if ("$".equals(path)) {
            throw new RuntimeException("cannot replace root path $");
        }
        StringUtils.Pair pathLeaf = getParentAndLeafPath(path);
        String left = pathLeaf.left;
        String right = pathLeaf.right;
        if (right.endsWith("]") && !right.endsWith("']")) { // json array
            int indexPos = right.lastIndexOf('[');
            int index = -1; // append, for case 'foo[]' (no integer, empty brackets)
            if (right.length() != indexPos + 1) {
                try {
                    index = Integer.valueOf(right.substring(indexPos + 1, right.length() - 1));
                } catch (Exception e) {
                    // index will be -1, default to append
                }
            }
            right = right.substring(0, indexPos);
            List list;
            String listPath;
            if (right.startsWith("[")) {
                listPath = left + right;
            } else {
                if ("".equals(left)) { // special case, root array
                    listPath = right;
                } else {
                    listPath = left + "." + right;
                }
            }
            try {
                list = doc.read(listPath);
                if (index == -1) {
                    index = list.size();
                }
                if (index < list.size()) {
                    if (remove) {
                        list.remove(index);
                    } else {
                        list.set(index, value);
                    }
                } else if (!remove) {
                    list.add(value);
                }
            } catch (Exception e) { // path does not exist or null
                if (!remove) {
                    list = new ArrayList();
                    list.add(value);
                    doc.put(left, right, list);
                }
            }
        } else {
            if (remove) {
                doc.delete(path);
            } else {
                if (right.startsWith("[")) {
                    right = right.substring(2, right.length() - 2);
                }
                if (!pathExists(doc, left)) {
                    createParents(doc, left);
                }
                doc.put(left, right, value);               
            }
        }
    }
    
    private static void createParents(DocumentContext doc, String path) {
        StringUtils.Pair pathLeaf = getParentAndLeafPath(path);
        String left = pathLeaf.left;
        String right = pathLeaf.right;        
        if ("".equals(left)) { // if root
            if (!"$".equals(right)) { // special case, root is array, typically "$[0]"
                doc.add("$", new LinkedHashMap()); // TODO we assume that second level is always object (not array of arrays)
            }
            return;
        }
        if (!pathExists(doc, left)) {
            createParents(doc, left);
        }        
        Object empty;      
        if (right.endsWith("]") && !right.endsWith("']")) {
            int pos = right.indexOf('[');
            right = right.substring(0, pos);
            List list = new ArrayList();
            list.add(new LinkedHashMap());
            empty = list;
        } else {
            empty = new LinkedHashMap();           
        }
        doc.put(left, right, empty);
    }
    
    public static boolean pathExists(DocumentContext doc, String path) {
        try {
            return doc.read(path) != null;
        } catch (PathNotFoundException pnfe) {
            return false;
        }    
    }

    public static DocumentContext fromYaml(String raw) {
        Yaml yaml = new Yaml();
        return JsonPath.parse(yaml.load(raw));
    }

    /**
     * use bracket notation if needed instead of dot notation
     */
    public static String buildPath(String parentPath, String key) {
        boolean needsQuotes = key.indexOf('-') != -1 || key.indexOf(' ') != -1 || key.indexOf('.') != -1;
        return needsQuotes ? parentPath + "['" + key + "']" : parentPath + '.' + key;
    }
    
    public static DocumentContext emptyJsonObject() {
        return toJsonDoc(new LinkedHashMap());
    }
    
    public static DocumentContext emptyJsonArray(int length) {
        List list = new ArrayList(length);
        for (int i = 0; i < length; i++) {
            list.add(new LinkedHashMap());
        }
        return toJsonDoc(list);
    }    

}
