// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.struct.consts;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.main.DecompilerContext;
import org.jetbrains.java.decompiler.modules.renamer.PoolInterceptor;
import org.jetbrains.java.decompiler.struct.gen.FieldDescriptor;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;
import org.jetbrains.java.decompiler.struct.gen.NewClassNameBuilder;
import org.jetbrains.java.decompiler.struct.gen.VarType;
import org.jetbrains.java.decompiler.util.DataInputFullStream;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("AssignmentToForLoopParameter")
public class ConstantPool implements NewClassNameBuilder {
  public static final int FIELD = 1;
  public static final int METHOD = 2;

  private final PooledConstant[] pool;
  private final PoolInterceptor interceptor;

  public ConstantPool(DataInputStream in) throws IOException {
    int size = in.readUnsignedShort();
    this.pool = new PooledConstant[size];
    @SuppressWarnings("unchecked")
    List<PooledConstant>[] passes = new List[]{
      new ArrayList<>(size / 3),
      new ArrayList<>(size / 5),
      new ArrayList<>()
    };

    // first pass: read the elements
    for (int i = 1; i < size; i++) {
      byte tag = (byte)in.readUnsignedByte();

      switch (tag) {
        case CodeConstants.CONSTANT_Utf8:
          pool[i] = new PrimitiveConstant(CodeConstants.CONSTANT_Utf8, in.readUTF());
          break;

        case CodeConstants.CONSTANT_Integer:
          pool[i] = new PrimitiveConstant(CodeConstants.CONSTANT_Integer, Integer.valueOf(in.readInt()));
          break;

        case CodeConstants.CONSTANT_Float:
          pool[i] = new PrimitiveConstant(CodeConstants.CONSTANT_Float, in.readFloat());
          break;

        case CodeConstants.CONSTANT_Long:
          pool[i] = new PrimitiveConstant(CodeConstants.CONSTANT_Long, in.readLong());
          i++;
          break;

        case CodeConstants.CONSTANT_Double:
          pool[i] = new PrimitiveConstant(CodeConstants.CONSTANT_Double, in.readDouble());
          i++;
          break;

        case CodeConstants.CONSTANT_Class:
        case CodeConstants.CONSTANT_String:
        case CodeConstants.CONSTANT_MethodType:
        case CodeConstants.CONSTANT_Module:
        case CodeConstants.CONSTANT_Package:
          PrimitiveConstant pkg = new PrimitiveConstant(tag, in.readUnsignedShort());
          pool[i] = pkg;
          passes[0].add(pkg);
          break;

        case CodeConstants.CONSTANT_NameAndType:
          LinkConstant nameAndType = new LinkConstant(tag, in.readUnsignedShort(), in.readUnsignedShort());
          pool[i] = nameAndType;
          passes[0].add(nameAndType);
          break;

        case CodeConstants.CONSTANT_Fieldref:
        case CodeConstants.CONSTANT_Methodref:
        case CodeConstants.CONSTANT_InterfaceMethodref:
        case CodeConstants.CONSTANT_InvokeDynamic:
          LinkConstant invokeDynamic = new LinkConstant(tag, in.readUnsignedShort(), in.readUnsignedShort());
          pool[i] = invokeDynamic;
          passes[1].add(invokeDynamic);
          break;

        case CodeConstants.CONSTANT_MethodHandle:
          LinkConstant methodHandle = new LinkConstant(tag, in.readUnsignedByte(), in.readUnsignedShort());
          pool[i] = methodHandle;
          passes[2].add(methodHandle);
          break;

        default:
          throw new RuntimeException("Invalid Constant Pool entry #" + i + " Type: " + tag);
      }
    }

    // resolving complex pool elements
    for (List<PooledConstant> pass : passes) {
      for (PooledConstant constant : pass) {
        constant.resolveConstant(this);
      }
    }

    // get global constant pool interceptor instance, if any available
    interceptor = DecompilerContext.getPoolInterceptor();
  }

  public String[] getClassElement(int elementType, String className, int nameIndex, int descriptorIndex) {
    String elementName = ((PrimitiveConstant)getConstant(nameIndex)).getString();
    String descriptor = ((PrimitiveConstant)getConstant(descriptorIndex)).getString();

    if (interceptor != null) {
      String oldClassName = interceptor.getOldName(className);
      if (oldClassName != null) {
        className = oldClassName;
      }

      String newElement = interceptor.getName(className + ' ' + elementName + ' ' + descriptor);
      if (newElement != null) {
        elementName = newElement.split(" ")[1];
      }

      String newDescriptor = buildNewDescriptor(elementType == FIELD, descriptor);
      if (newDescriptor != null) {
        descriptor = newDescriptor;
      }
    }

    return new String[]{elementName, descriptor};
  }

  public PooledConstant getConstant(int index) {
    return pool[index];
  }

  public PrimitiveConstant getPrimitiveConstant(int index) {
    PrimitiveConstant cn = (PrimitiveConstant)getConstant(index);

    if (cn != null && interceptor != null) {
      if (cn.type == CodeConstants.CONSTANT_Class) {
        String newName = buildNewClassname(cn.getString());
        if (newName != null) {
          cn = new PrimitiveConstant(CodeConstants.CONSTANT_Class, newName);
        }
      }
    }

    return cn;
  }

  public LinkConstant getLinkConstant(int index) {
    LinkConstant ln = (LinkConstant)getConstant(index);

    if (ln != null && interceptor != null &&
        (ln.type == CodeConstants.CONSTANT_Fieldref ||
         ln.type == CodeConstants.CONSTANT_Methodref ||
         ln.type == CodeConstants.CONSTANT_InterfaceMethodref)) {
      String newClassName = buildNewClassname(ln.classname);
      String newElement = interceptor.getName(ln.classname + ' ' + ln.elementname + ' ' + ln.descriptor);
      String newDescriptor = buildNewDescriptor(ln.type == CodeConstants.CONSTANT_Fieldref, ln.descriptor);
      //TODO: Fix newElement being null caused by ln.classname being a leaf class instead of the class that declared the field/method.
      //See the comments of IDEA-137253 for more information.
      if (newClassName != null || newElement != null || newDescriptor != null) {
        String className = newClassName == null ? ln.classname : newClassName;
        String elementName = newElement == null ? ln.elementname : newElement.split(" ")[1];
        String descriptor = newDescriptor == null ? ln.descriptor : newDescriptor;
        ln = new LinkConstant(ln.type, className, elementName, descriptor);
      }
    }

    return ln;
  }

  @Override
  public String buildNewClassname(String className) {
    VarType vt = new VarType(className, true);

    String newName = interceptor.getName(vt.value);
    if (newName != null) {
      StringBuilder buffer = new StringBuilder();
      if (vt.arrayDim > 0) {
        // No functional change, just a revert for J8 compatibility
        for (int i = 0; i < vt.arrayDim; i++) {
          buffer.append('[');
        }

        buffer.append('L').append(newName).append(';');
      }
      else {
        buffer.append(newName);
      }
      return buffer.toString();
    }

    return null;
  }

  private String buildNewDescriptor(boolean isField, String descriptor) {
    if (isField) {
      return FieldDescriptor.parseDescriptor(descriptor).buildNewDescriptor(this);
    }
    else {
      return MethodDescriptor.parseDescriptor(descriptor).buildNewDescriptor(this);
    }
  }
}
