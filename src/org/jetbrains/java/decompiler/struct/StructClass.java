// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.struct;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.struct.attr.StructGeneralAttribute;
import org.jetbrains.java.decompiler.struct.attr.StructGenericSignatureAttribute;
import org.jetbrains.java.decompiler.struct.attr.StructRecordAttribute;
import org.jetbrains.java.decompiler.struct.consts.ConstantPool;
import org.jetbrains.java.decompiler.struct.consts.PrimitiveConstant;
import org.jetbrains.java.decompiler.struct.gen.VarType;
import org.jetbrains.java.decompiler.struct.gen.generics.GenericClassDescriptor;
import org.jetbrains.java.decompiler.struct.gen.generics.GenericMain;
import org.jetbrains.java.decompiler.struct.gen.generics.GenericType;
import org.jetbrains.java.decompiler.struct.lazy.LazyLoader;
import org.jetbrains.java.decompiler.util.DataInputFullStream;
import org.jetbrains.java.decompiler.util.InterpreterUtil;
import org.jetbrains.java.decompiler.util.VBStyleCollection;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/*
  class_file {
    u4 magic;
    u2 minor_version;
    u2 major_version;
    u2 constant_pool_count;
    cp_info constant_pool[constant_pool_count-1];
    u2 access_flags;
    u2 this_class;
    u2 super_class;
    u2 interfaces_count;
    u2 interfaces[interfaces_count];
    u2 fields_count;
    field_info fields[fields_count];
    u2 methods_count;
    method_info methods[methods_count];
    u2 attributes_count;
    attribute_info attributes[attributes_count];
  }
*/
public class StructClass extends StructMember {
  public static StructClass create(DataInputFullStream in, boolean own, LazyLoader loader) throws IOException {
    in.discard(4);
    int minorVersion = in.readUnsignedShort();
    int majorVersion = in.readUnsignedShort();
    int bytecodeVersion = Math.max(majorVersion, CodeConstants.BYTECODE_JAVA_LE_4);

    ConstantPool pool = new ConstantPool(in);

    int accessFlags = in.readUnsignedShort();
    int thisClassIdx = in.readUnsignedShort();
    int superClassIdx = in.readUnsignedShort();
    String qualifiedName = pool.getPrimitiveConstant(thisClassIdx).getString();
    PrimitiveConstant superClass = pool.getPrimitiveConstant(superClassIdx);

    int interfaceCount = in.readUnsignedShort();
    int[] interfaces = new int[interfaceCount];
    String[] interfaceNames = new String[interfaceCount];
    for (int i = 0; i < interfaceCount; i++) {
      interfaces[i] = in.readUnsignedShort();
      interfaceNames[i] = pool.getPrimitiveConstant(interfaces[i]).getString();
    }

    VBStyleCollection<StructField, String> fields = readFields(in, pool, qualifiedName);
    VBStyleCollection<StructMethod, String> methods = readMethods(in, own, bytecodeVersion, pool, qualifiedName);
    Map<String, StructGeneralAttribute> attributes = readAttributes(in, pool);

    GenericClassDescriptor signature = null;
    if (DecompilerContext.getOption(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES)) {
      StructGenericSignatureAttribute signatureAttr = (StructGenericSignatureAttribute)attributes.get(StructGeneralAttribute.ATTRIBUTE_SIGNATURE.name);
      if (signatureAttr != null) {
        signature = GenericMain.parseClassSignature(qualifiedName, signatureAttr.getSignature());
      }
    }

    StructClass cl = new StructClass(
      accessFlags, attributes, qualifiedName, superClass, own, loader, minorVersion, majorVersion, interfaces, interfaceNames, fields, methods, signature);
    if (loader == null) cl.pool = pool;
    return cl;
  }

  private static VBStyleCollection<StructField, String> readFields(DataInputFullStream in, ConstantPool pool, String qualifiedName) throws IOException {
    int fieldCount = in.readUnsignedShort();
    VBStyleCollection<StructField, String>fields = new VBStyleCollection<>(fieldCount);
    for (int i = 0; i < fieldCount; i++) {
      StructField field = StructField.create(in, pool, qualifiedName);
      fields.addWithKey(field, InterpreterUtil.makeUniqueKey(field.getName(), field.getDescriptor()));
    }
    return fields;
  }

  private static VBStyleCollection<StructMethod, String> readMethods(DataInputFullStream in, boolean own, int bytecodeVersion, ConstantPool pool, String qualifiedName) throws IOException {
    int methodCount = in.readUnsignedShort();
    VBStyleCollection<StructMethod, String> methods = new VBStyleCollection<>(methodCount);
    for (int i = 0; i < methodCount; i++) {
      StructMethod method = StructMethod.create(in, pool, qualifiedName, bytecodeVersion, own);
      String key = InterpreterUtil.makeUniqueKey(method.getName(), method.getDescriptor());
      if (methods.containsKey(key)) {
        String fullName = qualifiedName + "." + method.getName() + method.getDescriptor();
        DecompilerContext.getLogger().writeMessage("Duplicate method " + fullName, IFernflowerLogger.Severity.WARN);
      }
      methods.addWithKey(method, key);
    }
    return methods;
  }

  public final String qualifiedName;
  public final PrimitiveConstant superClass;
  private final boolean own;
  private final LazyLoader loader;
  private final int minorVersion;
  private final int majorVersion;
  private final int[] interfaces;
  private final String[] interfaceNames;
  private final VBStyleCollection<StructField, String> fields;
  private final VBStyleCollection<StructMethod, String> methods;
  private final GenericClassDescriptor signature;

  private ConstantPool pool;

  private StructClass(int accessFlags,
                      Map<String, StructGeneralAttribute> attributes,
                      String qualifiedName,
                      PrimitiveConstant superClass,
                      boolean own,
                      LazyLoader loader,
                      int minorVersion,
                      int majorVersion,
                      int[] interfaces,
                      String[] interfaceNames,
                      VBStyleCollection<StructField, String> fields,
                      VBStyleCollection<StructMethod, String> methods,
                      GenericClassDescriptor signature) {
    super(accessFlags, attributes);
    this.qualifiedName = qualifiedName;
    this.superClass = superClass;
    this.own = own;
    this.loader = loader;
    this.minorVersion = minorVersion;
    this.majorVersion = majorVersion;
    this.interfaces = interfaces;
    this.interfaceNames = interfaceNames;
    this.fields = fields;
    this.methods = methods;
    this.signature = signature;
  }

  public boolean hasField(String name, String descriptor) {
    return getField(name, descriptor) != null;
  }

  public StructField getField(String name, String descriptor) {
    return fields.getWithKey(InterpreterUtil.makeUniqueKey(name, descriptor));
  }

  public StructMethod getMethod(String key) {
    return methods.getWithKey(key);
  }

  public StructMethod getMethod(String name, String descriptor) {
    return methods.getWithKey(InterpreterUtil.makeUniqueKey(name, descriptor));
  }

  public StructMethod getMethodRecursive(String name, String descriptor) {
    StructMethod ret = getMethod(name, descriptor);

    if (ret != null) {
      return ret;
    }

    if (superClass != null) {
      StructClass cls = DecompilerContext.getStructContext().getClass((String)superClass.value);
      if (cls != null) {
        ret = cls.getMethodRecursive(name, descriptor);
        if (ret != null) {
          return ret;
        }
      }
    }

    for (String intf : getInterfaceNames()) {
      StructClass cls = DecompilerContext.getStructContext().getClass(intf);
      if (cls != null) {
        ret = cls.getMethodRecursive(name, descriptor);
        if (ret != null) {
          return ret;
        }
      }
    }
    return null;
  }

  public String getInterface(int i) {
    return interfaceNames[i];
  }

  public void releaseResources() {
    if (loader != null) {
      pool = null;
    }
  }

  public ConstantPool getPool() {
    if (pool == null && loader != null) {
      pool = loader.loadPool(qualifiedName);
    }
    return pool;
  }

  /**
   * @return list of record components; null if this class is not a record
   */
  public List<StructRecordComponent> getRecordComponents() {
    StructRecordAttribute recordAttr = getAttribute(StructGeneralAttribute.ATTRIBUTE_RECORD);
    if (recordAttr == null) return null;
    return recordAttr.getComponents();
  }

  public int[] getInterfaces() {
    return interfaces;
  }

  public String[] getInterfaceNames() {
    return interfaceNames;
  }

  public VBStyleCollection<StructMethod, String> getMethods() {
    return methods;
  }

  public VBStyleCollection<StructField, String> getFields() {
    return fields;
  }

  public boolean isOwn() {
    return own;
  }

  public boolean isVersion5() {
    return (majorVersion > CodeConstants.BYTECODE_JAVA_LE_4 ||
            (majorVersion == CodeConstants.BYTECODE_JAVA_LE_4 && minorVersion > 0)); // FIXME: check second condition
  }

  public boolean isVersion8() {
    return majorVersion >= CodeConstants.BYTECODE_JAVA_8;
  }

  @Override
  public String toString() {
    return qualifiedName;
  }

  public GenericClassDescriptor getSignature() {
    return signature;
  }

  private Map<VarType, VarType> getGenericMap(VarType type) {
    if (this.signature == null || type == null || !type.isGeneric()) {
      return Collections.emptyMap();
    }
    GenericType gtype = (GenericType)type;
    if (gtype.getArguments().size() != this.signature.fparameters.size()) { //Invalid instance type?
      return Collections.emptyMap();
    }

    Map<VarType, VarType> ret = new HashMap<>();
    for (int x = 0; x < this.signature.fparameters.size(); x++) {
      VarType var = gtype.getArguments().get(x);
      if (var != null) {
        ret.put(GenericType.parse("T" + this.signature.fparameters.get(x) + ";"), var);
      }
    }
    return ret;
  }

  private Map<String, Map<VarType, VarType>> genericHiarachy;
  public Map<String, Map<VarType, VarType>> getAllGenerics() {
    if (genericHiarachy != null) {
      return genericHiarachy;
    }

    Map<String, Map<VarType, VarType>> ret = new HashMap<>();
    if (this.signature != null && !this.signature.fparameters.isEmpty()) {
      Map<VarType, VarType> mine = new HashMap<>();
      for (String par : this.signature.fparameters) {
        VarType type = GenericType.parse("T" + par + ";");
        mine.put(type, type);
      }
      ret.put(this.qualifiedName, mine);
    }

    Set<String> visited = new HashSet<>(); //Is there a better way? Is the signature forced to contain all interfaces?
    if (this.signature != null) {
      for (VarType intf : this.signature.superinterfaces) {
        visited.add((String)intf.value);

        StructClass cls = DecompilerContext.getStructContext().getClass((String)intf.value);
        if (cls != null) {
          Map<VarType, VarType> sig = cls.getGenericMap(intf);

          for (Entry<String, Map<VarType, VarType>> e : cls.getAllGenerics().entrySet()) {
            if (e.getValue().isEmpty()) {
              ret.put(e.getKey(), e.getValue());
            }
            else {
              Map<VarType, VarType> sub = new HashMap<>();
              for (Entry<VarType, VarType> e2 : e.getValue().entrySet()) {
                sub.put(e2.getKey(), sig.getOrDefault(e2.getValue(), e2.getValue()));
              }
              ret.put(e.getKey(), sub);
            }
          }
        }
      }
    }

    for (String intf : this.interfaceNames) {
      if (visited.contains(intf)) {
        continue;
      }
      StructClass cls = DecompilerContext.getStructContext().getClass(intf);
      if (cls != null) {
        ret.putAll(cls.getAllGenerics());
      }
    }

    if (this.superClass != null) {
      StructClass cls = DecompilerContext.getStructContext().getClass((String)this.superClass.value);
      if (cls != null) {
        Map<VarType, VarType> sig = this.signature == null ? Collections.emptyMap() : cls.getGenericMap(this.signature.superclass);
        if (sig.isEmpty()) {
          ret.putAll(cls.getAllGenerics());
        }
        else {
          for (Entry<String, Map<VarType, VarType>> e : cls.getAllGenerics().entrySet()) {
            if (e.getValue().isEmpty()) {
              ret.put(e.getKey(), e.getValue());
            }
            else {
              Map<VarType, VarType> sub = new HashMap<>();
              for (Entry<VarType, VarType> e2 : e.getValue().entrySet()) {
                sub.put(e2.getKey(), sig.getOrDefault(e2.getValue(), e2.getValue()));
              }
              ret.put(e.getKey(), sub);
            }
          }
        }
      }
    }

    this.genericHiarachy = ret.isEmpty() ? Collections.emptyMap() : ret;
    return this.genericHiarachy;
  }

  private List<StructClass> superClasses;
  public List<StructClass> getAllSuperClasses() {
    if (superClasses != null) {
      return superClasses;
    }

    List<StructClass> classList = new ArrayList<>();
    StructContext context = DecompilerContext.getStructContext();

    if (this.superClass != null) {
      StructClass cl = context.getClass(this.superClass.getString());
      while (cl != null) {
        classList.add(cl);
        if (cl.superClass == null) {
          break;
        }
        cl = context.getClass(cl.superClass.getString());
      }
    }

    superClasses = classList;
    return superClasses;
  }
}
