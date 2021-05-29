// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.struct.attr;

import org.jetbrains.java.decompiler.struct.consts.ConstantPool;
import org.jetbrains.java.decompiler.util.DataInputFullStream;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/*
  attribute_info {
    u2 attribute_name_index;
    u4 attribute_length;
    u1 info[attribute_length];
  }
*/
@SuppressWarnings("StaticInitializerReferencesSubClass")
public class StructGeneralAttribute {
  private static final Map<String, Supplier<? extends StructGeneralAttribute>> CONSTRUCTORS = new HashMap<>();
  public static final Key<StructCodeAttribute> ATTRIBUTE_CODE = new Key<>("Code", StructCodeAttribute::new);
  public static final Key<StructInnerClassesAttribute> ATTRIBUTE_INNER_CLASSES = new Key<>("InnerClasses", StructInnerClassesAttribute::new);
  public static final Key<StructGenericSignatureAttribute> ATTRIBUTE_SIGNATURE = new Key<>("Signature", StructGenericSignatureAttribute::new);
  public static final Key<StructAnnDefaultAttribute> ATTRIBUTE_ANNOTATION_DEFAULT = new Key<>("AnnotationDefault", StructAnnDefaultAttribute::new);
  public static final Key<StructExceptionsAttribute> ATTRIBUTE_EXCEPTIONS = new Key<>("Exceptions", StructExceptionsAttribute::new);
  public static final Key<StructEnclosingMethodAttribute> ATTRIBUTE_ENCLOSING_METHOD = new Key<>("EnclosingMethod", StructEnclosingMethodAttribute::new);
  public static final Key<StructAnnotationAttribute> ATTRIBUTE_RUNTIME_VISIBLE_ANNOTATIONS = new Key<>("RuntimeVisibleAnnotations", StructAnnotationAttribute::new);
  public static final Key<StructAnnotationAttribute> ATTRIBUTE_RUNTIME_INVISIBLE_ANNOTATIONS = new Key<>("RuntimeInvisibleAnnotations", StructAnnotationAttribute::new);
  public static final Key<StructAnnotationParameterAttribute> ATTRIBUTE_RUNTIME_VISIBLE_PARAMETER_ANNOTATIONS = new Key<>("RuntimeVisibleParameterAnnotations", StructAnnotationParameterAttribute::new);
  public static final Key<StructAnnotationParameterAttribute> ATTRIBUTE_RUNTIME_INVISIBLE_PARAMETER_ANNOTATIONS = new Key<>("RuntimeInvisibleParameterAnnotations", StructAnnotationParameterAttribute::new);
  public static final Key<StructTypeAnnotationAttribute> ATTRIBUTE_RUNTIME_VISIBLE_TYPE_ANNOTATIONS = new Key<>("RuntimeVisibleTypeAnnotations", StructTypeAnnotationAttribute::new);
  public static final Key<StructTypeAnnotationAttribute> ATTRIBUTE_RUNTIME_INVISIBLE_TYPE_ANNOTATIONS = new Key<>("RuntimeInvisibleTypeAnnotations", StructTypeAnnotationAttribute::new);
  public static final Key<StructLocalVariableTableAttribute> ATTRIBUTE_LOCAL_VARIABLE_TABLE = new Key<>("LocalVariableTable", StructLocalVariableTableAttribute::new);
  public static final Key<StructLocalVariableTypeTableAttribute> ATTRIBUTE_LOCAL_VARIABLE_TYPE_TABLE = new Key<>("LocalVariableTypeTable", StructLocalVariableTypeTableAttribute::new);
  public static final Key<StructConstantValueAttribute> ATTRIBUTE_CONSTANT_VALUE = new Key<>("ConstantValue", StructConstantValueAttribute::new);
  public static final Key<StructBootstrapMethodsAttribute> ATTRIBUTE_BOOTSTRAP_METHODS = new Key<>("BootstrapMethods", StructBootstrapMethodsAttribute::new);
  public static final Key<StructGeneralAttribute> ATTRIBUTE_SYNTHETIC = new Key<>("Synthetic", StructGeneralAttribute::new);
  public static final Key<StructGeneralAttribute> ATTRIBUTE_DEPRECATED = new Key<>("Deprecated", StructGeneralAttribute::new);
  public static final Key<StructLineNumberTableAttribute> ATTRIBUTE_LINE_NUMBER_TABLE = new Key<>("LineNumberTable", StructLineNumberTableAttribute::new);
  public static final Key<StructMethodParametersAttribute> ATTRIBUTE_METHOD_PARAMETERS = new Key<>("MethodParameters", StructMethodParametersAttribute::new);
  public static final Key<StructModuleAttribute> ATTRIBUTE_MODULE = new Key<>("Module", StructModuleAttribute::new);
  public static final Key<StructRecordAttribute> ATTRIBUTE_RECORD = new Key<>("Record", StructRecordAttribute::new);

  public static class Key<T extends StructGeneralAttribute> {
    public final String name;

    public Key(String name, Supplier<T> constructor) {
      this.name = name;
      CONSTRUCTORS.put(name, constructor);
    }
  }

  public static StructGeneralAttribute createAttribute(String name) {
    Supplier<? extends StructGeneralAttribute> constr = CONSTRUCTORS.get(name);
    if (constr != null) return constr.get();
    return null; // unsupported attribute
  }

  public void initContent(DataInputFullStream data, ConstantPool pool) throws IOException { }
}
