// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.main;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.main.ClassesProcessor.ClassNode;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.rels.ClassWrapper;
import org.jetbrains.java.decompiler.main.rels.MethodWrapper;
import org.jetbrains.java.decompiler.modules.decompiler.exps.*;
import org.jetbrains.java.decompiler.modules.decompiler.stats.RootStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statements;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPair;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.StructField;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.struct.gen.MethodDescriptor;
import org.jetbrains.java.decompiler.struct.gen.VarType;
import org.jetbrains.java.decompiler.util.InterpreterUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class InitializerProcessor {
  public static void extractInitializers(ClassWrapper wrapper) {
    MethodWrapper method = wrapper.getMethodWrapper(CodeConstants.CLINIT_NAME, "()V");
    if (method != null && method.root != null) {  // successfully decompiled static constructor
      extractStaticInitializers(wrapper, method);
    }

    extractDynamicInitializers(wrapper);

    // required e.g. if anonymous class is being decompiled as a standard one.
    // This can happen if InnerClasses attributes are erased
    liftConstructor(wrapper);

    if (DecompilerContext.getOption(IFernflowerPreferences.HIDE_EMPTY_SUPER)) {
      hideEmptySuper(wrapper);
    }
  }

  private static void liftConstructor(ClassWrapper wrapper) {
    for (MethodWrapper method : wrapper.getMethods()) {
      if (CodeConstants.INIT_NAME.equals(method.methodStruct.getName()) && method.root != null) {
        Statement firstData = Statements.findFirstData(method.root);
        if (firstData == null) {
          return;
        }

        int index = 0;
        List<Exprent> lstExprents = firstData.getExprents();

        for (Exprent exprent : lstExprents) {
          int action = 0;

          if (exprent.type == Exprent.EXPRENT_ASSIGNMENT) {
            AssignmentExprent assignExpr = (AssignmentExprent)exprent;
            if (assignExpr.getLeft().type == Exprent.EXPRENT_FIELD && assignExpr.getRight().type == Exprent.EXPRENT_VAR) {
              FieldExprent fExpr = (FieldExprent)assignExpr.getLeft();
              if (fExpr.getClassname().equals(wrapper.getClassStruct().qualifiedName)) {
                StructField structField = wrapper.getClassStruct().getField(fExpr.getName(), fExpr.getDescriptor().descriptorString);
                if (structField != null && structField.hasModifier(CodeConstants.ACC_FINAL)) {
                  action = 1;
                }
              }
            }
          }
          else if (index > 0 && exprent.type == Exprent.EXPRENT_INVOCATION &&
                   Statements.isInvocationInitConstructor((InvocationExprent)exprent, method, wrapper, true)) {
            // this() or super()
            lstExprents.add(0, lstExprents.remove(index));
            action = 2;
          }

          if (action != 1) {
            break;
          }

          index++;
        }
      }
    }
  }

  private static void hideEmptySuper(ClassWrapper wrapper) {
    for (MethodWrapper method : wrapper.getMethods()) {
      if (CodeConstants.INIT_NAME.equals(method.methodStruct.getName()) && method.root != null) {
        Statement firstData = Statements.findFirstData(method.root);
        if (firstData == null || firstData.getExprents().isEmpty()) {
          return;
        }

        Exprent exprent = firstData.getExprents().get(0);
        if (exprent.type == Exprent.EXPRENT_INVOCATION) {
          InvocationExprent invExpr = (InvocationExprent)exprent;
          if (Statements.isInvocationInitConstructor(invExpr, method, wrapper, false)) {
            List<VarVersionPair> mask = ExprUtil.getSyntheticParametersMask(invExpr.getClassname(), invExpr.getStringDescriptor(), invExpr.getLstParameters().size());
            boolean hideSuper = true;

            //searching for non-synthetic params
            for (int i = 0; i < invExpr.getDescriptor().params.length; ++i) {
              if (mask != null && mask.get(i) != null) {
                continue;
              }
              VarType type = invExpr.getDescriptor().params[i];
              if (type.type == CodeConstants.TYPE_OBJECT) {
                ClassNode node = DecompilerContext.getClassProcessor().getMapRootClasses().get(type.value);
                if (node != null && (node.type == ClassNode.CLASS_ANONYMOUS || (node.access & CodeConstants.ACC_SYNTHETIC) != 0)) {
                  break; // Should be last
                }
              }
              hideSuper = false; // found non-synthetic param so we keep the call
              break;
            }

            if (hideSuper) {
              firstData.getExprents().remove(0);
            }
          }
        }
      }
    }
  }

  public static void hideInitalizers(ClassWrapper wrapper) {
    // hide initializers with anon class arguments
    for (MethodWrapper method : wrapper.getMethods()) {
      StructMethod mt = method.methodStruct;
      String name = mt.getName();
      String desc = mt.getDescriptor();

      if (mt.isSynthetic() && CodeConstants.INIT_NAME.equals(name)) {
        MethodDescriptor md = MethodDescriptor.parseDescriptor(desc);
        if (md.params.length > 0) {
          VarType type = md.params[md.params.length - 1];
          if (type.type == CodeConstants.TYPE_OBJECT) {
            ClassNode node = DecompilerContext.getClassProcessor().getMapRootClasses().get(type.value);
            if (node != null && (node.type == ClassNode.CLASS_ANONYMOUS) || (node.access & CodeConstants.ACC_SYNTHETIC) != 0) {
              //TODO: Verify that the body is JUST a this([args]) call?
              wrapper.getHiddenMembers().add(InterpreterUtil.makeUniqueKey(name, desc));
            }
          }
        }
      }
    }
  }

  private static void extractStaticInitializers(ClassWrapper wrapper, MethodWrapper method) {
    RootStatement root = method.root;
    StructClass cl = wrapper.getClassStruct();
    Set<String> whitelist = new HashSet<String>();

    Statement firstData = Statements.findFirstData(root);
    if (firstData != null) {
      boolean inlineInitializers = cl.hasModifier(CodeConstants.ACC_INTERFACE) || cl.hasModifier(CodeConstants.ACC_ENUM);
      List<AssignmentExprent> exprentsToRemove = new ArrayList<>();//when we loop back through the list, stores ones we need to remove outside iterator loop
      Map<Integer, AssignmentExprent> nonFieldAssigns = new HashMap<>();
      Iterator<Exprent> itr = firstData.getExprents().iterator();
      while (itr.hasNext()) {
        Exprent exprent = itr.next();

        if (exprent.type == Exprent.EXPRENT_ASSIGNMENT) {
          AssignmentExprent assignExpr = (AssignmentExprent)exprent;
          if (assignExpr.getLeft().type == Exprent.EXPRENT_FIELD) {
            FieldExprent fExpr = (FieldExprent)assignExpr.getLeft();
            if (fExpr.isStatic() && fExpr.getClassname().equals(cl.qualifiedName) &&
                cl.hasField(fExpr.getName(), fExpr.getDescriptor().descriptorString)) {

              // interfaces fields should always be initialized inline
              String keyField = InterpreterUtil.makeUniqueKey(fExpr.getName(), fExpr.getDescriptor().descriptorString);
              boolean exprentIndependent = isExprentIndependent(assignExpr.getRight(), method, cl, whitelist, cl.getFields().getIndexByKey(keyField), true);
              if (inlineInitializers || exprentIndependent) {
                if (!wrapper.getStaticFieldInitializers().containsKey(keyField)) {
                  if (exprentIndependent) {
                    wrapper.getStaticFieldInitializers().addWithKey(assignExpr.getRight(), keyField);
                    whitelist.add(keyField);
                    itr.remove();
                  } else { //inlineInitializers
                    if (assignExpr.getRight() instanceof NewExprent){
                      NewExprent newExprent = (NewExprent) assignExpr.getRight();
                      Exprent instance = newExprent.getConstructor().getInstance();
                      if (instance instanceof VarExprent && nonFieldAssigns.containsKey(((VarExprent) instance).getIndex())){
                        AssignmentExprent nonFieldAssignment = nonFieldAssigns.remove(((VarExprent) instance).getIndex());
                        newExprent.getConstructor().setInstance(nonFieldAssignment.getRight());
                        exprentsToRemove.add(nonFieldAssignment);
                        wrapper.getStaticFieldInitializers().addWithKey(assignExpr.getRight(), keyField);
                        whitelist.add(keyField);
                        itr.remove();
                      } else {
                        DecompilerContext.getLogger().writeMessage("Don't know how to handle non independent "+assignExpr.getRight().getClass().getName(), IFernflowerLogger.Severity.ERROR);
                      }
                    } else {
                      DecompilerContext.getLogger().writeMessage("Don't know how to handle non independent "+assignExpr.getRight().getClass().getName(), IFernflowerLogger.Severity.ERROR);
                    }
                  }
                }
              }
            }
          } else if (inlineInitializers){
            DecompilerContext.getLogger().writeMessage("Found non field assignment when needing to force inline: "+assignExpr.toString(), IFernflowerLogger.Severity.TRACE);
            if (assignExpr.getLeft() instanceof VarExprent) {
              nonFieldAssigns.put(((VarExprent) assignExpr.getLeft()).getIndex(), assignExpr);
            } else {
              DecompilerContext.getLogger().writeMessage("Left isnt VarExprent :(", IFernflowerLogger.Severity.ERROR);
            }
          }
        } else if (inlineInitializers && cl.hasModifier(CodeConstants.ACC_INTERFACE)){
          DecompilerContext.getLogger().writeMessage("Non assignment found in initialiser when we're needing to inline all", IFernflowerLogger.Severity.ERROR);
        }
      }
      if (exprentsToRemove.size() > 0){
        firstData.getExprents().removeAll(exprentsToRemove);
      }
    }
  }

  private static void extractDynamicInitializers(ClassWrapper wrapper) {
    StructClass cl = wrapper.getClassStruct();

    boolean isAnonymous = DecompilerContext.getClassProcessor().getMapRootClasses().get(cl.qualifiedName).type == ClassNode.CLASS_ANONYMOUS;

    List<List<Exprent>> lstFirst = new ArrayList<>();
    List<MethodWrapper> lstMethodWrappers = new ArrayList<>();

    for (MethodWrapper method : wrapper.getMethods()) {
      if (CodeConstants.INIT_NAME.equals(method.methodStruct.getName()) && method.root != null) { // successfully decompiled constructor
        Statement firstData = Statements.findFirstData(method.root);
        if (firstData == null || firstData.getExprents().isEmpty()) {
          continue;
        }

        Exprent exprent = firstData.getExprents().get(0);
        if (!isAnonymous) { // FIXME: doesn't make sense
          if (exprent.type != Exprent.EXPRENT_INVOCATION ||
              !Statements.isInvocationInitConstructor((InvocationExprent)exprent, method, wrapper, false)) {
            continue;
          }
        }
        lstFirst.add(firstData.getExprents());
        lstMethodWrappers.add(method);
      }
    }

    if (lstFirst.isEmpty()) {
      return;
    }

    Set<String> whitelist = new HashSet<String>(wrapper.getStaticFieldInitializers().getLstKeys());
    int prev_fidx = 0;

    while (true) {
      String fieldWithDescr = null;
      Exprent value = null;

      for (int i = 0; i < lstFirst.size(); i++) {
        List<Exprent> lst = lstFirst.get(i);

        if (lst.size() < (isAnonymous ? 1 : 2)) {
          return;
        }

        Exprent exprent = lst.get(isAnonymous ? 0 : 1);

        boolean found = false;

        if (exprent.type == Exprent.EXPRENT_ASSIGNMENT) {
          AssignmentExprent assignExpr = (AssignmentExprent)exprent;
          if (assignExpr.getLeft().type == Exprent.EXPRENT_FIELD) {
            FieldExprent fExpr = (FieldExprent)assignExpr.getLeft();
            if (!fExpr.isStatic() && fExpr.getClassname().equals(cl.qualifiedName) &&
                cl.hasField(fExpr.getName(), fExpr.getDescriptor().descriptorString)) { // check for the physical existence of the field. Could be defined in a superclass.

              String fieldKey = InterpreterUtil.makeUniqueKey(fExpr.getName(), fExpr.getDescriptor().descriptorString);
              int fidx = cl.getFields().getIndexByKey(fieldKey);
              if (prev_fidx <= fidx && isExprentIndependent(assignExpr.getRight(), lstMethodWrappers.get(i), cl, whitelist, fidx, false)) {
                prev_fidx = fidx;
                if (fieldWithDescr == null) {
                  fieldWithDescr = fieldKey;
                  value = assignExpr.getRight();
                }
                else {
                  if (!fieldWithDescr.equals(fieldKey) ||
                      !value.equals(assignExpr.getRight())) {
                    return;
                  }
                }
                found = true;
              }
            }
          }
        }

        if (!found) {
          return;
        }
      }

      if (!wrapper.getDynamicFieldInitializers().containsKey(fieldWithDescr)) {
        wrapper.getDynamicFieldInitializers().addWithKey(value, fieldWithDescr);
        whitelist.add(fieldWithDescr);

        for (List<Exprent> lst : lstFirst) {
          lst.remove(isAnonymous ? 0 : 1);
        }
      }
      else {
        return;
      }
    }
  }

  private static boolean isExprentIndependent(Exprent exprent, MethodWrapper method, StructClass cl, Set<String> whitelist, int fidx, boolean isStatic) {
    List<Exprent> lst = exprent.getAllExprents(true);
    lst.add(exprent);

    for (Exprent expr : lst) {
      switch (expr.type) {
        case Exprent.EXPRENT_VAR:
          VarVersionPair varPair = new VarVersionPair((VarExprent)expr);
          if (!method.varproc.getExternalVars().contains(varPair)) {
            String varName = method.varproc.getVarName(varPair);
            if (!varName.equals("this") && !varName.endsWith(".this")) { // FIXME: remove direct comparison with strings
              return false;
            }
          }
          break;
        case Exprent.EXPRENT_FIELD:
          FieldExprent fexpr = (FieldExprent)expr;
          if (cl.hasField(fexpr.getName(), fexpr.getDescriptor().descriptorString)) {
            String key = InterpreterUtil.makeUniqueKey(fexpr.getName(), fexpr.getDescriptor().descriptorString);
            if (isStatic) {
              // There is a very stupid section of the JLS
              if (!fexpr.isStatic()) {
                return false;
              } else if (cl.getFields().getIndexByKey(key) >= fidx) {
                fexpr.forceQualified(true);
              }
            } else {
              if (!whitelist.contains(key)) {
                return false;
              } else if (cl.getFields().getIndexByKey(key) > fidx) {
                return false;
              }
            }
          }
          else if (!fexpr.isStatic() && fexpr.getInstance() == null) {
            return false;
          }
          break;
        case Exprent.EXPRENT_NEW:
          if (!isNewExprentIndependent((NewExprent)expr, cl, fidx)) {
            return false;
          }
      }
    }

    return true;
  }

  // Verifies that a lambda used to initialize a static field does not reference
  // another static field defined later in the class
  private static boolean isNewExprentIndependent(NewExprent nexpr, StructClass cl, int fidx) {
    boolean isStatic = cl.getFields().get(fidx).hasModifier(CodeConstants.ACC_STATIC);
    if (isStatic && nexpr.isLambda() && !nexpr.isMethodReference()) {
      ClassNode child = DecompilerContext.getClassProcessor().getMapRootClasses().get(nexpr.getNewType().value);
      MethodWrapper wrapper = child.parent.getWrapper().getMethods().getWithKey(child.lambdaInformation.content_method_key);

      Set<Exprent> s = new HashSet<>();
      wrapper.getOrBuildGraph().iterateExprentsDeep(e -> {
        if (e.type == Exprent.EXPRENT_FIELD || e.type == Exprent.EXPRENT_NEW)
          s.add(e);
        return 0;
      });
      return s.stream().allMatch(e -> {
        switch (e.type) {
          case Exprent.EXPRENT_FIELD:
            FieldExprent fe = (FieldExprent)e;
            if (fe.isStatic() && cl.hasField(fe.getName(), fe.getDescriptor().descriptorString)) {
              String key = InterpreterUtil.makeUniqueKey(fe.getName(), fe.getDescriptor().descriptorString);
              if (fe.getInstance() == null && cl.getFields().getIndexByKey(key) > fidx) {
                return false;
              }
            }
            break;
          case Exprent.EXPRENT_NEW:
            return isNewExprentIndependent((NewExprent)e, cl, fidx);
        }

        return true;
      });
    }

    return true;
  }
}
