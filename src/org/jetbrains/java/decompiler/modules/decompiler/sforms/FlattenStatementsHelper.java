// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler.modules.decompiler.sforms;

import org.jetbrains.java.decompiler.modules.decompiler.StatEdge;
import org.jetbrains.java.decompiler.modules.decompiler.exps.Exprent;
import org.jetbrains.java.decompiler.modules.decompiler.stats.*;

import java.util.*;
import java.util.Map.Entry;


public class FlattenStatementsHelper {

  // statement.id, node.id(direct), node.id(continue)
  private final Map<Integer, DestNodePair> mapDestinationNodes = new HashMap<>();

  // node.id(source), statement.id(destination), edge type
  private final List<Edge> listEdges = new ArrayList<>();

  // node.id(exit), [node.id(source), statement.id(destination)]
  private final Map<String, List<String[]>> mapShortRangeFinallyPathIds = new HashMap<>();

  // node.id(exit), [node.id(source), statement.id(destination)]
  private final Map<String, List<String[]>> mapLongRangeFinallyPathIds = new HashMap<>();

  // positive if branches
  private final Map<String, Integer> mapPosIfBranch = new HashMap<>();

  private DirectGraph graph;

  private RootStatement root;

  public DirectGraph buildDirectGraph(RootStatement root) {

    this.root = root;

    graph = new DirectGraph();

    flattenStatement();

    // dummy exit node
    Statement dummyexit = root.getDummyExit();
    DirectNode node = new DirectNode(DirectNode.NODE_DIRECT, dummyexit, dummyexit.id.toString());
    node.exprents = new ArrayList<>();
    graph.nodes.addWithKey(node, node.id);
    mapDestinationNodes.put(dummyexit.id, new DestNodePair(node, null));

    setEdges();

    graph.first = mapDestinationNodes.get(root.id).getDirect();
    graph.sortReversePostOrder();

    return graph;
  }

  static class StatementStackEntry {
    public final Statement statement;
    public final LinkedList<StackEntry> stackFinally;
    public final List<Exprent> tailExprents;

    public int statementIndex;
    public int edgeIndex;
    public List<StatEdge> succEdges;

    StatementStackEntry(Statement statement, LinkedList<StackEntry> stackFinally, List<Exprent> tailExprents) {
      this.statement = statement;
      this.stackFinally = stackFinally;
      this.tailExprents = tailExprents;
    }
  }

  private DirectNode processBasicBlockStatement(StatementStackEntry statEntry, Statement stat, List<StatEdge> lstSuccEdges) {
    DirectNode node = new DirectNode(DirectNode.NODE_DIRECT, stat, (BasicBlockStatement) stat);
    if (stat.getExprents() != null) {
      node.exprents = stat.getExprents();
    }
    graph.nodes.putWithKey(node, node.id);
    mapDestinationNodes.put(stat.id, new DestNodePair(node, null));

    lstSuccEdges.addAll(stat.getSuccessorEdges(Statement.STATEDGE_DIRECT_ALL));
    DirectNode sourcenode = node;

    List<Exprent> tailExprentList = statEntry.tailExprents;

    if (tailExprentList != null) {
      DirectNode tail = new DirectNode(DirectNode.NODE_TAIL, stat, stat.id + "_tail");
      tail.exprents = tailExprentList;
      graph.nodes.putWithKey(tail, tail.id);

      mapDestinationNodes.put(-stat.id, new DestNodePair(tail, null));
      listEdges.add(new Edge(node.id, -stat.id, StatEdge.TYPE_REGULAR));

      sourcenode = tail;
    }

    // 'if' statement: record positive branch
    if (stat.getLastBasicType() == Statement.LASTBASICTYPE_IF) {
      mapPosIfBranch.put(sourcenode.id, lstSuccEdges.get(0).getDestination().id);
    }

    return sourcenode;
  }

  private void processTryCatchStatement(LinkedList<StatementStackEntry> lstStackStatements, Statement stat, LinkedList<StackEntry> stackFinally) {
    DirectNode firstnd = new DirectNode(DirectNode.NODE_TRY, stat, stat.id + "_try");

    if (stat.type == Statement.TYPE_TRYCATCH) {
      CatchStatement catchStat = (CatchStatement) stat;
      if (catchStat.getTryType() == CatchStatement.RESORCES) {
        firstnd.exprents = catchStat.getResources();
      }
    }

    mapDestinationNodes.put(stat.id, new DestNodePair(firstnd, null));
    graph.nodes.putWithKey(firstnd, firstnd.id);

    LinkedList<StatementStackEntry> lst = new LinkedList<>();

    for (Statement st : stat.getStats()) {
      listEdges.add(new Edge(firstnd.id, st.id, StatEdge.TYPE_REGULAR));

      LinkedList<StackEntry> stack = stackFinally;
      if (stat.type == Statement.TYPE_CATCHALL && ((CatchAllStatement) stat).isFinally()) {
        stack = new LinkedList<>(stackFinally);

        if (st == stat.getFirst()) { // catch head
          stack.add(new StackEntry((CatchAllStatement) stat, Boolean.FALSE));
        } else { // handler
          stack.add(new StackEntry((CatchAllStatement) stat, Boolean.TRUE, StatEdge.TYPE_BREAK,
            root.getDummyExit(), st, st, firstnd, firstnd, true));
        }
      }
      lst.add(new StatementStackEntry(st, stack, null));
    }

    lstStackStatements.addAll(0, lst);
  }

  private DirectNode processDoStatement(LinkedList<StatementStackEntry> lstStackStatements, StatementStackEntry statEntry, Statement stat, LinkedList<StackEntry> stackFinally, int statementBreakIndex, List<StatEdge> lstSuccEdges) {
    if (statementBreakIndex == 0) {
      statEntry.statementIndex = 1;
      lstStackStatements.addFirst(statEntry);
      lstStackStatements.addFirst(new StatementStackEntry(stat.getFirst(), stackFinally, null));
      return null;
    }

    DirectNode nd = mapDestinationNodes.get(stat.getFirst().id).getDirect();

    DoStatement dostat = (DoStatement) stat;
    int looptype = dostat.getLooptype();

    if (looptype == DoStatement.LOOP_DO) {
      mapDestinationNodes.put(stat.id, new DestNodePair(nd, nd));
      return null;
    }

    lstSuccEdges.add(stat.getSuccessorEdges(Statement.STATEDGE_DIRECT_ALL).get(0));  // exactly one edge

    switch (looptype) {
      case DoStatement.LOOP_WHILE:
      case DoStatement.LOOP_DOWHILE: {
        DirectNode node = new DirectNode(DirectNode.NODE_CONDITION, stat, stat.id + "_cond");
        node.exprents = dostat.getConditionExprentList();
        graph.nodes.putWithKey(node, node.id);

        listEdges.add(new Edge(node.id, stat.getFirst().id, StatEdge.TYPE_REGULAR));

        if (looptype == DoStatement.LOOP_WHILE) {
          mapDestinationNodes.put(stat.id, new DestNodePair(node, node));
        } else {
          mapDestinationNodes.put(stat.id, new DestNodePair(nd, node));

          boolean found = false;
          for (Edge edge : listEdges) {
            if (edge.statid.equals(stat.id) && edge.edgetype == StatEdge.TYPE_CONTINUE) {
              found = true;
              break;
            }
          }
          if (!found) {
            listEdges.add(new Edge(nd.id, stat.id, StatEdge.TYPE_CONTINUE));
          }
        }
        return node;
      }
      case DoStatement.LOOP_FOR:
      case DoStatement.LOOP_FOREACH: {
        DirectNode nodeinit = new DirectNode(DirectNode.NODE_INIT, stat, stat.id + "_init");
        if (dostat.getInitExprent() != null) {
          nodeinit.exprents = dostat.getInitExprentList();
        }
        graph.nodes.putWithKey(nodeinit, nodeinit.id);

        DirectNode nodecond = new DirectNode(DirectNode.NODE_CONDITION, stat, stat.id + "_cond");
        if (looptype != DoStatement.LOOP_FOREACH) {
          nodecond.exprents = dostat.getConditionExprentList();
        }
        graph.nodes.putWithKey(nodecond, nodecond.id);

        DirectNode nodeinc = new DirectNode(DirectNode.NODE_INCREMENT, stat, stat.id + "_inc");
        nodeinc.exprents = dostat.getIncExprentList();
        graph.nodes.putWithKey(nodeinc, nodeinc.id);

        mapDestinationNodes.put(stat.id, new DestNodePair(nodeinit, nodeinc));
        mapDestinationNodes.put(-stat.id, new DestNodePair(nodecond, null));

        listEdges.add(new Edge(nodecond.id, stat.getFirst().id, StatEdge.TYPE_REGULAR));
        listEdges.add(new Edge(nodeinit.id, -stat.id, StatEdge.TYPE_REGULAR));
        listEdges.add(new Edge(nodeinc.id, -stat.id, StatEdge.TYPE_REGULAR));

        boolean found = false;
        for (Edge edge : listEdges) {
          if (edge.statid.equals(stat.id) && edge.edgetype == StatEdge.TYPE_CONTINUE) {
            found = true;
            break;
          }
        }
        if (!found) {
          listEdges.add(new Edge(nd.id, stat.id, StatEdge.TYPE_CONTINUE));
        }

        return nodecond;
      }
    }
    return null;
  }

  private DirectNode processOtherStatement(LinkedList<StatementStackEntry> lstStackStatements, StatementStackEntry statEntry, Statement stat, LinkedList<StackEntry> stackFinally, int statementBreakIndex, List<StatEdge> lstSuccEdges) {
    int statsize = stat.getStats().size();
    if (stat.type == Statement.TYPE_SYNCRONIZED) {
      statsize = 2;  // exclude the handler if synchronized
    }

    if (statementBreakIndex <= statsize) {
      List<Exprent> tailexprlst = null;

      switch (stat.type) {
        case Statement.TYPE_SYNCRONIZED:
          tailexprlst = ((SynchronizedStatement) stat).getHeadexprentList();
          break;
        case Statement.TYPE_SWITCH:
          tailexprlst = ((SwitchStatement) stat).getHeadexprentList();
          break;
        case Statement.TYPE_IF:
          tailexprlst = ((IfStatement) stat).getHeadexprentList();
          break;
      }

      if (statementBreakIndex < statsize) {
        statEntry.statementIndex = statementBreakIndex + 1;
        lstStackStatements.addFirst(statEntry);
        lstStackStatements.addFirst(
          new StatementStackEntry(stat.getStats().get(statementBreakIndex), stackFinally,
            (statementBreakIndex == 0 && tailexprlst != null && tailexprlst.get(0) != null) ? tailexprlst : null));
        return null;
      }

      DirectNode node = mapDestinationNodes.get(stat.getFirst().id).getDirect();
      mapDestinationNodes.put(stat.id, new DestNodePair(node, null));

      if (stat instanceof IfStatement && ((IfStatement) stat).iftype == IfStatement.IFTYPE_IF && !stat.getAllSuccessorEdges().isEmpty()) {
        lstSuccEdges.add(stat.getSuccessorEdges(Statement.STATEDGE_DIRECT_ALL).get(0));  // exactly one edge
        //noinspection ConstantConditions
        return tailexprlst.get(0) == null ? node : graph.nodes.getWithKey(node.id + "_tail");
      }
    }
    return null;
  }

  private DirectNode processStatement(LinkedList<StatementStackEntry> lstStackStatements, StatementStackEntry statEntry, List<StatEdge> lstSuccEdges) {
    Statement stat = statEntry.statement;
    switch (stat.type) {
      case Statement.TYPE_BASICBLOCK:
        return processBasicBlockStatement(statEntry, stat, lstSuccEdges);
      case Statement.TYPE_CATCHALL:
      case Statement.TYPE_TRYCATCH:
        processTryCatchStatement(lstStackStatements, stat, statEntry.stackFinally);
        return null;
      case Statement.TYPE_DO:
        return processDoStatement(lstStackStatements, statEntry, stat, statEntry.stackFinally, statEntry.statementIndex, lstSuccEdges);
      case Statement.TYPE_SYNCRONIZED:
      case Statement.TYPE_SWITCH:
      case Statement.TYPE_IF:
      case Statement.TYPE_SEQUENCE:
      case Statement.TYPE_ROOT:
        return processOtherStatement(lstStackStatements, statEntry, stat, statEntry.stackFinally, statEntry.statementIndex, lstSuccEdges);
    }
    return null;
  }

  private void flattenStatement() {
    LinkedList<StatementStackEntry> lstStackStatements = new LinkedList<>();

    lstStackStatements.add(new StatementStackEntry(root, new LinkedList<>(), null));

    mainloop:
    while (!lstStackStatements.isEmpty()) {

      StatementStackEntry statEntry = lstStackStatements.removeFirst();

      LinkedList<StackEntry> stackFinally = statEntry.stackFinally;

      List<StatEdge> lstSuccEdges = new ArrayList<>();

      if (statEntry.succEdges != null) continue;
      DirectNode sourcenode = processStatement(lstStackStatements, statEntry, lstSuccEdges);

      // no successor edges
      if (sourcenode != null) {

        if (statEntry.succEdges != null) {
          lstSuccEdges = statEntry.succEdges;
        }

        for (int edgeindex = statEntry.edgeIndex; edgeindex < lstSuccEdges.size(); edgeindex++) {

          StatEdge edge = lstSuccEdges.get(edgeindex);

          LinkedList<StackEntry> stack = new LinkedList<>(stackFinally);

          int edgetype = edge.getType();
          Statement destination = edge.getDestination();

          DirectNode finallyShortRangeSource = sourcenode;
          DirectNode finallyLongRangeSource = sourcenode;
          Statement finallyShortRangeEntry = null;
          Statement finallyLongRangeEntry = null;

          boolean isFinallyMonitorExceptionPath = false;

          boolean isFinallyExit = false;

          while (true) {

            StackEntry entry = null;
            if (!stack.isEmpty()) {
              entry = stack.getLast();
            }

            boolean created = true;

            if (entry == null) {
              saveEdge(sourcenode, destination, edgetype, isFinallyExit ? finallyShortRangeSource : null, finallyLongRangeSource,
                       finallyShortRangeEntry, finallyLongRangeEntry, isFinallyMonitorExceptionPath);
            }
            else {

              CatchAllStatement catchall = entry.catchstatement;

              if (entry.state) { // finally handler statement
                if (edgetype == StatEdge.TYPE_FINALLYEXIT) {

                  stack.removeLast();
                  destination = entry.destination;
                  edgetype = entry.edgetype;

                  finallyShortRangeSource = entry.finallyShortRangeSource;
                  finallyLongRangeSource = entry.finallyLongRangeSource;
                  finallyShortRangeEntry = entry.finallyShortRangeEntry;
                  finallyLongRangeEntry = entry.finallyLongRangeEntry;

                  isFinallyExit = true;
                  isFinallyMonitorExceptionPath = (catchall.getMonitor() != null) & entry.isFinallyExceptionPath;

                  created = false;
                }
                else {
                  if (!catchall.containsStatementStrict(destination)) {
                    stack.removeLast();
                    created = false;
                  }
                  else {
                    saveEdge(sourcenode, destination, edgetype, isFinallyExit ? finallyShortRangeSource : null, finallyLongRangeSource,
                             finallyShortRangeEntry, finallyLongRangeEntry, isFinallyMonitorExceptionPath);
                  }
                }
              }
              else { // finally protected try statement
                if (!catchall.containsStatementStrict(destination)) {
                  saveEdge(sourcenode, catchall.getHandler(), StatEdge.TYPE_REGULAR, isFinallyExit ? finallyShortRangeSource : null,
                           finallyLongRangeSource, finallyShortRangeEntry, finallyLongRangeEntry, isFinallyMonitorExceptionPath);

                  stack.removeLast();
                  stack.add(new StackEntry(catchall, Boolean.TRUE, edgetype, destination, catchall.getHandler(),
                                           finallyLongRangeEntry == null ? catchall.getHandler() : finallyLongRangeEntry,
                                           sourcenode, finallyLongRangeSource, false));

                  statEntry.edgeIndex = edgeindex + 1;
                  statEntry.succEdges = lstSuccEdges;
                  lstStackStatements.addFirst(statEntry);
                  lstStackStatements.addFirst(new StatementStackEntry(catchall.getHandler(), stack, null));

                  continue mainloop;
                }
                else {
                  saveEdge(sourcenode, destination, edgetype, isFinallyExit ? finallyShortRangeSource : null, finallyLongRangeSource,
                           finallyShortRangeEntry, finallyLongRangeEntry, isFinallyMonitorExceptionPath);
                }
              }
            }

            if (created) {
              break;
            }
          }
        }
      }
    }
  }

  private void saveEdge(DirectNode sourcenode,
                        Statement destination,
                        int edgetype,
                        DirectNode finallyShortRangeSource,
                        DirectNode finallyLongRangeSource,
                        Statement finallyShortRangeEntry,
                        Statement finallyLongRangeEntry,
                        boolean isFinallyMonitorExceptionPath) {

    if (edgetype != StatEdge.TYPE_FINALLYEXIT) {
      listEdges.add(new Edge(sourcenode.id, destination.id, edgetype));
    }

    if (finallyShortRangeSource != null) {
      boolean isContinueEdge = (edgetype == StatEdge.TYPE_CONTINUE);

      mapShortRangeFinallyPathIds.computeIfAbsent(sourcenode.id, k -> new ArrayList<>()).add(new String[]{
        finallyShortRangeSource.id,
        destination.id.toString(),
        finallyShortRangeEntry.id.toString(),
        isFinallyMonitorExceptionPath ? "1" : null,
        isContinueEdge ? "1" : null});

      mapLongRangeFinallyPathIds.computeIfAbsent(sourcenode.id, k -> new ArrayList<>()).add(new String[]{
        finallyLongRangeSource.id,
        destination.id.toString(),
        finallyLongRangeEntry.id.toString(),
        isContinueEdge ? "1" : null});
    }
  }

  private void setEdges() {

    for (Edge edge : listEdges) {

      String sourceid = edge.sourceid;
      Integer statid = edge.statid;

      DirectNode source = graph.nodes.getWithKey(sourceid);

      DestNodePair destPair = mapDestinationNodes.get(statid);
      DirectNode dest = edge.edgetype == StatEdge.TYPE_CONTINUE ? destPair.getContinue() : destPair.getDirect();

      if (!source.succs.contains(dest)) {
        source.succs.add(dest);
      }

      if (!dest.preds.contains(source)) {
        dest.preds.add(source);
      }

      if (mapPosIfBranch.containsKey(sourceid) && !statid.equals(mapPosIfBranch.get(sourceid))) {
        graph.mapNegIfBranch.put(sourceid, dest.id);
      }
    }

    for (int i = 0; i < 2; i++) {
      for (Entry<String, List<String[]>> ent : (i == 0 ? mapShortRangeFinallyPathIds : mapLongRangeFinallyPathIds).entrySet()) {

        List<String[]> lst = ent.getValue();
        List<FinallyPathWrapper> newLst = new ArrayList<>(lst.size());

        for (String[] arr : lst) {

          boolean isContinueEdge = arr[i == 0 ? 4 : 3] != null;

          DestNodePair destPair = mapDestinationNodes.get(Integer.parseInt(arr[1]));
          DirectNode dest = isContinueEdge ? destPair.getContinue() : destPair.getDirect();
          DirectNode enter = mapDestinationNodes.get(Integer.parseInt(arr[2])).getDirect();

          newLst.add(new FinallyPathWrapper(arr[0], dest.id, enter.id));

          if (i == 0 && arr[3] != null) {
            graph.mapFinallyMonitorExceptionPathExits.put(ent.getKey(), dest.id);
          }
        }

        if (!newLst.isEmpty()) {
          (i == 0 ? graph.mapShortRangeFinallyPaths : graph.mapLongRangeFinallyPaths).put(ent.getKey(),
                                                                                          new ArrayList<>(
                                                                                            new HashSet<>(newLst)));
        }
      }
    }
  }

  public DirectNode getDirectDestinationNode(Integer id) {
    return mapDestinationNodes.get(id).getDirect();
  }

  public static final class FinallyPathWrapper {
    public final String source;
    public final String destination;
    public final String entry;

    private FinallyPathWrapper(String source, String destination, String entry) {
      this.source = source;
      this.destination = destination;
      this.entry = entry;
    }

    @Override
    public boolean equals(Object o) {
      if (o == this) return true;
      if (!(o instanceof FinallyPathWrapper)) return false;

      FinallyPathWrapper fpw = (FinallyPathWrapper)o;
      return (source + ":" + destination + ":" + entry).equals(fpw.source + ":" + fpw.destination + ":" + fpw.entry);
    }

    @Override
    public int hashCode() {
      return (source + ":" + destination + ":" + entry).hashCode();
    }

    @Override
    public String toString() {
      return source + "->(" + entry + ")->" + destination;
    }
  }


  private static class StackEntry {

    public final CatchAllStatement catchstatement;
    public final boolean state;
    public final int edgetype;
    public final boolean isFinallyExceptionPath;

    public final Statement destination;
    public final Statement finallyShortRangeEntry;
    public final Statement finallyLongRangeEntry;
    public final DirectNode finallyShortRangeSource;
    public final DirectNode finallyLongRangeSource;

    StackEntry(CatchAllStatement catchstatement,
                      boolean state,
                      int edgetype,
                      Statement destination,
                      Statement finallyShortRangeEntry,
                      Statement finallyLongRangeEntry,
                      DirectNode finallyShortRangeSource,
                      DirectNode finallyLongRangeSource,
                      boolean isFinallyExceptionPath) {

      this.catchstatement = catchstatement;
      this.state = state;
      this.edgetype = edgetype;
      this.isFinallyExceptionPath = isFinallyExceptionPath;

      this.destination = destination;
      this.finallyShortRangeEntry = finallyShortRangeEntry;
      this.finallyLongRangeEntry = finallyLongRangeEntry;
      this.finallyShortRangeSource = finallyShortRangeSource;
      this.finallyLongRangeSource = finallyLongRangeSource;
    }

    StackEntry(CatchAllStatement catchstatement, boolean state) {
      this(catchstatement, state, -1, null, null, null, null, null, false);
    }
  }

  private static class Edge {
    public final String sourceid;
    public final Integer statid;
    public final int edgetype;

    Edge(String sourceid, Integer statid, int edgetype) {
      this.sourceid = sourceid;
      this.statid = statid;
      this.edgetype = edgetype;
    }
  }

  private static class DestNodePair {
    private final DirectNode direct;
    private final DirectNode cont;
    
    DestNodePair(DirectNode direct, DirectNode cont) {
      this.direct = direct;
      this.cont = cont;
    }

    public DirectNode getDirect() {
      return direct;
    }

    public DirectNode getContinue() {
      return cont;
    }
  }
}
