package org.apache.calcite.adapter.jdbc;

import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelVisitor;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.core.*;
import org.apache.calcite.rel.externalize.RelWriterImpl;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.ControlFlowException;
import org.apache.calcite.util.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class MultiCloudDataManager {
    private static final Logger logger = LoggerFactory.getLogger(MultiCloudDataManager.class);

    public static Set<MultiCloudField<String, String, String>> findFields(final RelNode node) {
        //logger.debug("RelVisitor:Init");

        final Set<MultiCloudField<String, String, String>> usedFields = Sets.newLinkedHashSet();

        Set<RelOptTable> tables = Sets.newLinkedHashSet();
        List<Pair<RexNode, String>> projects = new ArrayList<>();
        // TODO: Remove if not used
        RelWriter rw = new RelWriterImpl(new PrintWriter(System.out, true));
        final int[] i = {0};
        final RelVisitor visitor = new RelVisitor() {
            @Override
            public void visit(final RelNode node, final int ordinal, final RelNode parent) {
                if (parent != null) {
                    logger.debug("RelVisitor.Parent\t" + i[0] + " : " + parent.getDigest());
                }
                logger.debug("\t\tRelVisitor.Node\t" + i[0] + " : " + node.getDigest());
                for (RelNode child : node.getInputs()) {
                    logger.debug("\t\t\t\tRelVisitor.Child\t" + i[0] + " : " + child.getDigest());
                }

                // TODO: get all the tables in the separate relVisitor before this one so you have that information here.
                if (node instanceof TableScan) {
                    tables.add(node.getTable());
                } // EXPLAIN: I got all the tables

                if (node instanceof Project) { // EXPLAIN: if it's a Project, we want to find him a matching TableScan/Join to get Schema and Table information
                    List<Pair<RexNode, String>> namedProjects = ((Project) node).getNamedProjects();
                    projects.addAll(namedProjects);

                    new RelVisitor() {

                        @Override
                        // EXPLAIN: initial input is a child of pNode = project node. go through the tree and discover first scan, that should be the correct one
                        public void visit(RelNode pNode, int ordinal, RelNode parent) {
                            // FIXME: this doesn't work for PROJECT->FILTER->SCAN type queries

                            if (pNode instanceof TableScan) {
                                // EXPLAIN: if it's a Table Scan, this is most likely the correct one

                                final TableScan scan = (TableScan) pNode;
                                // EXPLAIN: get information about table
                                RelOptTable table = scan.getTable();
                                String schemaName = table.getQualifiedName().get(0);
                                String tableName = table.getQualifiedName().get(1);

                                // TODO: add check that Project->Fields are subset of TableScan->Fields to confirm this is a correct TableScan - in case of Join, this is more complicated
                                for (RelDataTypeField relDataTypeField : table.getRowType().getFieldList()) {
                                    relDataTypeField.getName();
                                }

                                // EXPLAIN: go through the list of fields in Project and add them to the usedFields, together with discovered TableScan schema and table names.
                                for (Pair<RexNode, String> field : namedProjects) {
                                    String fieldName = field.getValue();
                                    usedFields.add(MultiCloudField.of(schemaName, tableName, fieldName));
                                }
                            } else if (pNode instanceof Join) {
                                // EXPLAIN: if it's a Join, we need to start new relVisitors on each branch
                                // TODO: add relVisitors on each child branch of JOIN

                            }

                            // EXPLAIN: otherwise, continue to next child
                            super.visit(pNode, ordinal, parent);
                        }
                    }.go(node.getInput(0)); // EXPLAIN: Project always has single child
                } // EXPLAIN: I got all the projects

                // TODO: can we map them now?

                i[0]++;
                super.visit(node, ordinal, parent); // visit children
            }
        };
        visitor.go(node);

        logger.debug("RelVisitor:Done\n\t" + usedFields.stream()
                .map(MultiCloudField::toString)
                .collect(Collectors.joining("\n\t")));
        return usedFields; //usedTables;
    }

    public class MapOperandToProjectRelVisitor extends RelVisitor {
        private Project root;
        private RelNode scan; // can be TableScan, or Join

        public MapOperandToProjectRelVisitor(RelNode project) {
            this.root = (Project) project;
        }

    }

    /**
     * This class is a helper to check whether a materialized view rebuild
     * can be transformed from INSERT OVERWRITE to INSERT INTO.
     * <p>
     * We are verifying that:
     * 1) the rewriting is rooted by legal operators (Filter and Project)
     * before reaching a Union operator,
     * 2) the left branch uses the MV that we are trying to rebuild and
     * legal operators (Filter and Project), and
     * 3) the right branch only uses legal operators (i.e., Filter, Project,
     * Join, and TableScan)
     */
    public class MaterializedViewRewritingRelVisitor extends RelVisitor {

        //private static final Logger LOG = LoggerFactory.getLogger(MaterializedViewRewritingRelVisitor.class);


        final Set<MultiCloudField<String, String, String>> usedFields;
        private boolean containsAggregate;
        private boolean rewritingAllowed;

        public MaterializedViewRewritingRelVisitor() {
            usedFields = Sets.newLinkedHashSet();
        }

        @Override
        public void visit(RelNode node, int ordinal, RelNode parent) {
            if (node instanceof Aggregate) {
                this.containsAggregate = true;
                // Aggregate mode - it should be followed by union
                // that we need to analyze
                RelNode input = node.getInput(0);
                if (input instanceof Union) {
                    check((Union) input);
                }
            } else if (node instanceof Union) {
                // Non aggregate mode - analyze union operator
                check((Union) node);
            } else if (node instanceof Project) {
                // Project operator, we can continue
                super.visit(node, ordinal, parent);
            }
            throw new ReturnedValue(false);
        }

        private void check(Union union) {
            // We found the Union
            if (union.getInputs().size() != 2) {
                // Bail out
                throw new ReturnedValue(false);
            }
            // First branch should have the query (with write ID filter conditions)
            new RelVisitor() {
                @Override
                public void visit(RelNode node, int ordinal, RelNode parent) {
                    if (node instanceof TableScan ||
                            node instanceof Filter ||
                            node instanceof Project ||
                            node instanceof Join) {
                        // We can continue
                        super.visit(node, ordinal, parent);
                    } else if (node instanceof Aggregate && containsAggregate) {
                        // We can continue
                        super.visit(node, ordinal, parent);
                    } else {
                        throw new ReturnedValue(false);
                    }
                }
            }.go(union.getInput(0));
            // Second branch should only have the MV
            new RelVisitor() {
                @Override
                public void visit(RelNode node, int ordinal, RelNode parent) {
                    if (node instanceof TableScan) {
                        // We can continue
                        // TODO: Need to check that this is the same MV that we are rebuilding
                        RelOptTable hiveTable = (RelOptTable) node.getTable();
                        //if (!hiveTable.getHiveTableMD().isMaterializedView()) {
                        // If it is not a materialized view, we do not rewrite it
                        //throw new ReturnedValue(false);
                        if (containsAggregate
                            //        && !AcidUtils.isFullAcidTable(hiveTable.getHiveTableMD())
                        ) {
                            // If it contains an aggregate and it is not a full acid table,
                            // we do not rewrite it (we need MERGE support)
                            throw new ReturnedValue(false);
                        }
                    } else if (node instanceof Project) {
                        // We can continue
                        super.visit(node, ordinal, parent);
                    } else {
                        throw new ReturnedValue(false);
                    }
                }
            }.go(union.getInput(1));
            // We pass all the checks, we can rewrite
            throw new ReturnedValue(true);
        }

        /**
         * Starts an iteration.
         */
        public RelNode go(RelNode p) {
            try {
                visit(p, 0, null);
            } catch (ReturnedValue e) {
                // Rewriting cannot be performed
                rewritingAllowed = e.value;
            }
            return p;
        }

        public boolean isContainsAggregate() {
            return containsAggregate;
        }

        public boolean isRewritingAllowed() {
            return rewritingAllowed;
        }
    }


    /**
     * Exception used to interrupt a visitor walk.
     */
    private static class ReturnedValue extends ControlFlowException {
        private final boolean value;

        public ReturnedValue(boolean value) {
            this.value = value;
        }
    }
}
