package org.flymine.objectstore.ojb;


/* ====================================================================
 * The Apache Software License, Version 1.1
 *
 * Copyright (c) 2001 The Apache Software Foundation.  All rights
 * reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. The end-user documentation included with the redistribution,
 *    if any, must include the following acknowledgment:
 *       "This product includes software developed by the
 *        Apache Software Foundation (http://www.apache.org/)."
 *    Alternately, this acknowledgment may appear in the software itself,
 *    if and wherever such third-party acknowledgments normally appear.
 *
 * 4. The names "Apache" and "Apache Software Foundation" and
 *    "Apache ObjectRelationalBridge" must not be used to endorse or promote products
 *    derived from this software without prior written permission. For
 *    written permission, please contact apache@apache.org.
 *
 * 5. Products derived from this software may not be called "Apache",
 *    "Apache ObjectRelationalBridge", nor may "Apache" appear in their name, without
 *    prior written permission of the Apache Software Foundation.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL THE APACHE SOFTWARE FOUNDATION OR
 * ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 */

//import java.util.HashSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.flymine.objectstore.query.Query;
import org.flymine.objectstore.query.QueryClass;
import org.flymine.objectstore.query.QueryNode;
import org.flymine.objectstore.query.QueryEvaluable;
import org.flymine.objectstore.query.QueryField;
import org.flymine.objectstore.query.QueryExpression;
import org.flymine.objectstore.query.QueryFunction;
import org.flymine.objectstore.query.QueryValue;
import org.flymine.objectstore.query.FromElement;
import org.flymine.objectstore.query.Constraint;
import org.flymine.objectstore.query.ConstraintSet;
import org.flymine.objectstore.query.SimpleConstraint;
import org.flymine.objectstore.query.SubqueryConstraint;
import org.flymine.objectstore.query.ClassConstraint;
import org.flymine.objectstore.query.ContainsConstraint;
import org.flymine.objectstore.query.QueryReference;
import org.flymine.objectstore.query.QueryObjectReference;
import org.flymine.objectstore.query.QueryCollectionReference;


import org.apache.ojb.broker.accesslayer.sql.SqlStatement;
import org.apache.ojb.broker.accesslayer.conversions.Boolean2IntFieldConversion;
import org.apache.ojb.broker.metadata.ClassDescriptor;
import org.apache.ojb.broker.metadata.FieldDescriptor;
import org.apache.ojb.broker.metadata.DescriptorRepository;
import org.apache.ojb.broker.metadata.CollectionDescriptor;
import org.apache.ojb.broker.metadata.ObjectReferenceDescriptor;

/**
 * Code to generate and sql statement...
 *
 * @author Richard Smith
 * @author Matthew Wakeling
 */


public class FlymineSqlSelectStatement implements SqlStatement
{
    private Query query;
    private DescriptorRepository dr;
    private int start;
    private int limit;
    private boolean isSubQuery;
    private boolean isAConstraint;

    private boolean needWhereComma;
    private String whereText;
    private boolean needFromComma;
    private String fromText;
    private Map classToFieldNames;
    private Map indirectionTables;

    /**
     * This is a FUDGE - here, we are assuming that the OJB_CONCRETE_CLASS field will always have
     * a column name of "CLASS".
     */
    public static final String OJB_CONCRETE_CLASS_COLUMN = "CLASS";
    
    /**
     * Constructor requires a FlyMine Query and associated array of ClassDescriptors.
     * Should be a ClassDescriptor for each class in FROM clause of query.
     *
     * @param query a flymine query
     * @param dr DescriptorRepository for the database
     * @param start the number of rows to skip at the beginning
     * @param limit the maximum number of rows to return
     */
    public FlymineSqlSelectStatement(Query query, DescriptorRepository dr, int start, int limit) {
        this.query = query;
        this.dr = dr;
        this.start = start;
        this.limit = limit;
        this.isSubQuery = false;
        this.isAConstraint = false;
    }

    /**
     * Constructor requires a FlyMine Query and associated array of ClassDescriptors.
     * Should be a ClassDescriptor for each class in FROM clause of query.
     *
     * @param query a flymine query
     * @param dr DescriptorRepository for the database
     */
    public FlymineSqlSelectStatement(Query query, DescriptorRepository dr) {
        this(query, dr, false);
    }

    /**
     * Constructor requires a FlyMine Query and associated array of ClassDescriptors.
     * Should be a ClassDescriptor for each class in FROM clause of query.
     *
     * @param query a flymine query
     * @param dr DescriptorRepository for the database
     * @param isAConstraint true if this is a query that is part of a subquery constraint
     */
    public FlymineSqlSelectStatement(Query query, DescriptorRepository dr, boolean isAConstraint) {
        this.query = query;
        this.dr = dr;
        this.start = 0;
        this.limit = 0;
        this.isSubQuery = true;
        this.isAConstraint = isAConstraint;
    }

    /**
     * Returns a String containing the entire SELECT list of the query.
     *
     * @return the SELECT list
     */
    protected String buildSelectComponent() {
        String retval = "";
        boolean needComma = false;
        List select = query.getSelect();
        Iterator selectIter = select.iterator();
        while (selectIter.hasNext()) {
            QueryNode node = (QueryNode) selectIter.next();
            if (needComma) {
                retval += ", ";
            }
            needComma = true;
            if (node instanceof QueryClass) {
                retval += queryClassToString((QueryClass) node, true, isAConstraint);
            } else {
                retval += queryEvaluableToString((QueryEvaluable) node) + " AS "
                    + query.getAliases().get(node);
            }
        }
        return retval;
    }

    /**
     * Converts a QueryClass into the SELECT list fields required to represent it in the SQL query.
     *
     * @param node the QueryClass
     * @param aliases whether to include aliases in the field list
     * @param primaryOnly whether to only list primary keys
     * @return the String representation
     */
    protected String queryClassToString(QueryClass node, boolean aliases, boolean primaryOnly) {
        String retval = "";
        boolean needComma = false;
        // It's a class - find its class descriptor, then iterate through its fields.
        // This QueryClass should be aliased as described by Query.getAliases().
        String alias = (String) query.getAliases().get(node);
        boolean done = false;
        ClassDescriptor cld = dr.getDescriptorFor(node.getType());
        if (cld == null) {
            throw (new IllegalArgumentException("Couldn't find class descriptor for "
                        + node.getType()));
        }
        // Now cld is the ClassDescriptor of the node, and alias is the alias
        TreeSet fieldnames;
        if (primaryOnly) {
            FieldDescriptor fields[] = cld.getPkFields();
            if (fields == null) {
                throw (new IllegalArgumentException("Array of field descriptors for "
                            + node.getType() + " is null"));
            }
            fieldnames = new TreeSet();
            for (int i = 0; i < fields.length; i++) {
                FieldDescriptor field = fields[i];
                fieldnames.add(field.getColumnName());
            }
        } else {
            fieldnames = (TreeSet) classToFieldNames.get(node.getType());
        }
        Iterator fieldnameIter = fieldnames.iterator();
        while (fieldnameIter.hasNext()) {
            String fieldname = (String) fieldnameIter.next();
            if (needComma) {
                retval += ", ";
            }
            needComma = true;
            retval += alias + "." + fieldname + (aliases ? " AS " + alias + fieldname : "");
        }
        return retval;
    }

    /**
     * Converts a QueryEvaluable into a SELECT list field.
     *
     * @param node the QueryEvaluable
     * @return the String representation
     */
    protected String queryEvaluableToString(QueryEvaluable node) {
        if (node instanceof QueryField) {
            // It's a field - find its FieldDescriptor by looking at its QueryClass, then its
            // ClassDescriptor.
            QueryField nodeF = (QueryField) node;
            FromElement nodeClass = nodeF.getFromElement();
            String classAlias = (String) query.getAliases().get(nodeClass);
            //boolean done = false;
            //ClassDescriptor cld = null;
            //for (int i = 0; (i<clds.length) && (! done) ; i++) {
            //    cld = clds[i];
            //    if (cld.getClassOfObject().equals(nodeClass.getType())) {
            //        done = true;
            //    }
            //}
            //if (cld == null) {
            //    throw (new Exception("Couldn't find class descriptor for class "
            //                + nodeClass.getType()));
            //}

            // Now cld is the ClassDescriptor for the node's class. Now need to find node's
            // FieldDescriptor.

            return classAlias + "." + nodeF.getFieldName();
        } else if (node instanceof QueryExpression) {
            QueryExpression nodeE = (QueryExpression) node;
            if (nodeE.getOperation() == QueryExpression.SUBSTRING) {
                QueryEvaluable arg1 = nodeE.getArg1();
                QueryEvaluable arg2 = nodeE.getArg2();
                QueryEvaluable arg3 = nodeE.getArg3();

                return "Substr(" + queryEvaluableToString(arg1) + ", "
                    + queryEvaluableToString(arg2) + ", " + queryEvaluableToString(arg3) + ")";
            } else {
                QueryEvaluable arg1 = nodeE.getArg1();
                QueryEvaluable arg2 = nodeE.getArg2();
                String op = null;
                switch (nodeE.getOperation()) {
                    case QueryExpression.ADD:
                        op = " + ";
                        break;
                    case QueryExpression.SUBTRACT:
                        op = " - ";
                        break;
                    case QueryExpression.MULTIPLY:
                        op = " * ";
                        break;
                    case QueryExpression.DIVIDE:
                        op = " / ";
                        break;
                    default:
                        throw (new IllegalArgumentException("Invalid QueryExpression operation: "
                                    + nodeE.getOperation()));
                }
                return "(" + queryEvaluableToString(arg1) + op + queryEvaluableToString(arg2) + ")";
            }
        } else if (node instanceof QueryFunction) {
            QueryFunction nodeF = (QueryFunction) node;
            switch (nodeF.getOperation()) {
                case QueryFunction.COUNT:
                    return "COUNT(*)";
                case QueryFunction.SUM:
                    return "SUM(" + queryEvaluableToString(nodeF.getParam()) + ")";
                case QueryFunction.AVERAGE:
                    return "AVG(" + queryEvaluableToString(nodeF.getParam()) + ")";
                case QueryFunction.MIN:
                    return "MIN(" + queryEvaluableToString(nodeF.getParam()) + ")";
                case QueryFunction.MAX:
                    return "MAX(" + queryEvaluableToString(nodeF.getParam()) + ")";
                default:
                    throw (new IllegalArgumentException("Invalid QueryFunction operation: "
                                + nodeF.getOperation()));
            }
        } else if (node instanceof QueryValue) {
            QueryValue nodeV = (QueryValue) node;
            Object value = nodeV.getValue();
            return objectToString(value);
        } else {
            throw (new IllegalArgumentException("Invalid QueryEvaluable: " + node.toString()));
        }
    }

    /**
     * Converts an Object into a SQL String.
     *
     * @param value the object to convert
     * @return the String representation
     */
    public static String objectToString(Object value) {
        if (value instanceof Date) {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            return "'" + format.format((Date) value) + "'";
        } else if (value instanceof Number) {
            return value.toString();
        } else if (value instanceof String) {
            return "'" + value + "'";
        } else if (value instanceof Boolean) {
            return (new Boolean2IntFieldConversion()).javaToSql(value).toString();
        }
        throw (new IllegalArgumentException("Invalid Object in QueryValue: "
                    + value.toString()));
    }

    /**
     * Builds the FROM list for the SQL query.
     */
    protected void buildFromComponent() {
        Set fromElements = query.getFrom();
        Iterator fromIter = fromElements.iterator();
        while (fromIter.hasNext()) {
            if (needFromComma) {
                fromText += ", ";
            }
            needFromComma = true;
            Object fromElement = fromIter.next();
            if (fromElement instanceof QueryClass) {
                QueryClass qc = (QueryClass) fromElement;
                ClassDescriptor cld = dr.getDescriptorFor(qc.getType());
                String alias = (String) query.getAliases().get(qc);
                ArrayList subclasses = new ArrayList(cld.getExtentClasses());
                subclasses.add(cld.getClassOfObject());
                TreeSet allColumnNames = new TreeSet();
                HashMap tableNameToColumns = new HashMap();
                HashMap tableNameToClasses = new HashMap();
                Iterator subclassIter = subclasses.iterator();
                while (subclassIter.hasNext()) {
                    Class subclass = (Class) subclassIter.next();
                    ClassDescriptor subclassDesc = dr.getDescriptorFor(subclass);
                    if (!subclassDesc.isInterface()) {
                        String tableName = subclassDesc.getFullTableName();
                        if (!tableNameToColumns.containsKey(tableName)) {
                            tableNameToColumns.put(tableName, new HashSet());
                        }
                        Set tableColumns = (Set) tableNameToColumns.get(tableName);
                        FieldDescriptor fields[] = subclassDesc.getFieldDescriptions();
                        for (int i = 0; i < fields.length; i++) {
                            String columnName = fields[i].getColumnName();
                            allColumnNames.add(columnName);
                            tableColumns.add(columnName);
                        }
                        if (subclassDesc.getSuperClass() == null) {
                            // This class is an absolute superclass.
                            tableNameToClasses.put(tableName, subclassDesc.getClassNameOfObject());
                        } else {
                            if (!tableNameToClasses.containsKey(tableName)) {
                                tableNameToClasses.put(tableName, new HashSet());
                            }
                            Object classList = tableNameToClasses.get(tableName);
                            if (classList instanceof Set) {
                                ((Set) classList).add(subclassDesc.getClassNameOfObject());
                            }
                        }
                    }
                }
                // At this point, the Set allColumnNames, and the Maps tableNameToColumns and
                // tableNameToClasses should be fully set up. Now, we see if there's only one table.
                classToFieldNames.put(qc.getType(), allColumnNames);
                if (tableNameToColumns.size() == 1) {
                    // Lucky! there is only one table, so we don't need to do a UNION.
                    fromText += cld.getFullTableName() + " AS " + alias;
                    // Now, we see if we have to add any conditions to the WHERE clause.
                    Object classList = tableNameToClasses.get(cld.getFullTableName());
                    if (classList instanceof Set) {
                        // Yes, we do.
                        whereText += (needWhereComma ? " AND " : " WHERE ");
                        needWhereComma = true;
                        whereText += "(";
                        boolean needWhereOrGroupComma = false;
                        Iterator classIter = ((Set) classList).iterator();
                        while (classIter.hasNext()) {
                            String className = (String) classIter.next();
                            whereText += (needWhereOrGroupComma ? " OR " : "");
                            needWhereOrGroupComma = true;
                            whereText += alias + "." + OJB_CONCRETE_CLASS_COLUMN + " = '"
                                + className + "'";
                        }
                        whereText += ")";
                    }
                } else {
                    // Unlucky - we have to do a UNION.
                    allColumnNames.add(OJB_CONCRETE_CLASS_COLUMN);
                    boolean needUnionGroupComma = false;
                    Iterator tableIter = tableNameToColumns.entrySet().iterator();
                    while (tableIter.hasNext()) {
                        Map.Entry tableEntry = (Map.Entry) tableIter.next();
                        String tableName = (String) tableEntry.getKey();
                        Set tableColumns = (Set) tableEntry.getValue();
                        fromText += (needUnionGroupComma ? " UNION SELECT " : "(SELECT ");
                        needUnionGroupComma = true;
                        boolean needSelectListComma = false;
                        Iterator columnIter = allColumnNames.iterator();
                        while (columnIter.hasNext()) {
                            String columnName = (String) columnIter.next();
                            fromText += (needSelectListComma ? ", " : "");
                            needSelectListComma = true;
                            if (tableColumns.contains(columnName)) {
                                fromText += columnName;
                            } else {
                                if (columnName == OJB_CONCRETE_CLASS_COLUMN) {
                                    fromText += "'"
                                        + ((String) tableNameToClasses.get(tableName))
                                        + "' AS " + columnName;
                                } else {
                                    fromText += "NULL AS " + columnName;
                                }
                            }
                        }
                        fromText += " FROM " + tableName;
                        // Now see if we need any conditions
                        Object classList = tableNameToClasses.get(tableName);
                        if (classList instanceof Set) {
                            // Yes, we do.
                            boolean needUnionWhereComma = false;
                            Iterator classIter = ((Set) classList).iterator();
                            while (classIter.hasNext()) {
                                String className = (String) classIter.next();
                                fromText += (needUnionWhereComma ? " OR " : " WHERE ");
                                needUnionWhereComma = true;
                                fromText += OJB_CONCRETE_CLASS_COLUMN + " = '" + className + "'";
                            }
                        }
                    }
                    fromText += ") AS " + alias;
                }
            } else {
                Query q = (Query) fromElement;
                String alias = (String) query.getAliases().get(q);
                fromText += "(" + (new FlymineSqlSelectStatement(q, dr, false)).getStatement()
                    + ") AS " + alias;
            }
        }
    }

    /**
     * Builds the WHERE clause for the SQL query.
     */
    protected void buildWhereClause() {
        // TODO:
        Constraint c = query.getConstraint();
        if (c != null) {
            whereText += (needWhereComma ? " AND " : " WHERE ");
            needWhereComma = true;
            whereText += constraintToString(c);
        }
        Iterator indirectIter = indirectionTables.entrySet().iterator();
        while (indirectIter.hasNext()) {
            Map.Entry indirectEntry = (Map.Entry) indirectIter.next();
            String alias = (String) indirectEntry.getKey();
            String name = (String) indirectEntry.getValue();
            if (needFromComma) {
                fromText += ", ";
            }
            needFromComma = true;
            fromText += name + " AS " + alias;
        }
    }

    /**
     * Converts a Constraint object into a String suitable for putting in an SQL query.
     *
     * @param c the Constraint object
     * @return the converted String
     */
    protected String constraintToString(Constraint c) {
        if (c instanceof ConstraintSet) {
            return constraintSetToString((ConstraintSet) c);
        } else if (c instanceof SimpleConstraint) {
            return simpleConstraintToString((SimpleConstraint) c);
        } else if (c instanceof SubqueryConstraint) {
            return subqueryConstraintToString((SubqueryConstraint) c);
        } else if (c instanceof ClassConstraint) {
            return classConstraintToString((ClassConstraint) c);
        } else if (c instanceof ContainsConstraint) {
            return containsConstraintToString((ContainsConstraint) c);
        }
        throw (new IllegalArgumentException("Unknown constraint type: " + c));
    }


    /**
     * Converts a ConstraintSet object into a String suitable for putting in an SQL query.
     *
     * @param cs the ConstraintSet object
     * @return the converted String
     */
    protected String constraintSetToString(ConstraintSet cs) {
        String retval = (cs.isNegated() ? "( NOT (" : "(");
        boolean needComma = false;
        Set constraints = cs.getConstraints();
        Iterator constraintIter = constraints.iterator();
        while (constraintIter.hasNext()) {
            Constraint subC = (Constraint) constraintIter.next();
            if (needComma) {
                retval +=  (cs.getDisjunctive() ? " OR " : " AND ");
            }
            needComma = true;
            retval += constraintToString(subC);
        }
        return retval + (cs.isNegated() ? "))" : ")");
    }

    /**
     * Converts a SimpleConstraint object into a String suitable for putting in an SQL query.
     *
     * @param sc the SimpleConstraint object
     * @return the converted String
     */
    protected String simpleConstraintToString(SimpleConstraint sc) {
        if ((sc.getType() == SimpleConstraint.IS_NULL)
                || (sc.getType() == SimpleConstraint.IS_NOT_NULL)) {
            return queryEvaluableToString(sc.getArg1()) + sc.getOpString();
        }
        return queryEvaluableToString(sc.getArg1()) + sc.getOpString()
            + queryEvaluableToString(sc.getArg2());
    }

    /**
     * Converts a SubqueryConstraint object into a String suitable for putting in an SQL query.
     *
     * @param sc the SubqueryConstraint object
     * @return the converted String
     */
    protected String subqueryConstraintToString(SubqueryConstraint sc) {
        Query q = sc.getQuery();
        QueryEvaluable qe = sc.getQueryEvaluable();
        QueryClass cls = sc.getQueryClass();
        if (qe != null) {
            return queryEvaluableToString(qe) + (sc.isNotIn() ? " NOT IN (" : " IN (")
                + (new FlymineSqlSelectStatement(q, dr, true)).getStatement() + ")";
        }
        return queryClassToString(cls, false, true) + (sc.isNotIn() ? " NOT IN (" : " IN (")
            + (new FlymineSqlSelectStatement(q, dr, true)).getStatement() + ")";
    }

    /**
     * Converts a ClassConstraint object into a String suitable for putting in an SQL query.
     *
     * @param cc the ClassConstraint object
     * @return the converted String
     */
    protected String classConstraintToString(ClassConstraint cc) {
        QueryClass arg1 = cc.getArg1();
        String alias1 = ((String) query.getAliases().get(arg1)) + ".";
        QueryClass arg2QC = cc.getArg2QueryClass();
        String alias2 = null;
        if (arg2QC != null) {
            alias2 = ((String) query.getAliases().get(arg2QC)) + ".";
        }
        Object arg2O = cc.getArg2Object();
        ClassDescriptor cld = dr.getDescriptorFor(arg1.getType());
        if (cld == null) {
            throw (new IllegalArgumentException("Couldn't find class descriptor for "
                        + arg1.getType()));
        }
        FieldDescriptor fields[] = cld.getPkFields();
        String retval = (cc.isNotEqual() ? "( NOT (" : "(");
        boolean needComma = false;
        for (int i = 0; i < fields.length; i++) {
            FieldDescriptor field = fields[i];
            String columnname = field.getColumnName();
            if (needComma) {
                retval += " AND ";
            }
            needComma = true;
            if (arg2QC != null) {
                retval += alias1 + columnname + " = " + alias2 + columnname;
            } else {
                retval += alias1 + columnname + " = "
                    + objectToString(field.getPersistentField().get(arg2O));
            }
        }
        return retval + (cc.isNotEqual() ? "))" : ")");
    }

    /**
     * Converts a ContainsConstraint object into a String suitable for putting in an SQL query.
     *
     * @param cc the ContainsConstraint object
     * @return the converted String
     */
    protected String containsConstraintToString(ContainsConstraint cc) {
        QueryReference arg1 = cc.getReference();
        QueryClass arg2 = cc.getQueryClass();
        ClassDescriptor arg1Class = dr.getDescriptorFor(arg1.getQueryClass().getType());
        if (arg1Class == null) {
            throw (new IllegalArgumentException("Couldn't find class descriptor for "
                        + arg1.getQueryClass().getType()));
        }
        ClassDescriptor arg2Class = dr.getDescriptorFor(arg2.getType());
        if (arg2Class == null) {
            throw (new IllegalArgumentException("Couldn't find class descriptor for "
                        + arg2.getType()));
        }
        String thisAlias = ((String) query.getAliases().get(arg1.getQueryClass()))
            + ".";
        String thatAlias = ((String) query.getAliases().get(arg2)) + ".";
        // Now, the available variables are:
        // arg1Class    the ClassDescriptor for arg1 (the QueryReference)
        // arg2Class    the ClassDescriptor for arg2 (the QueryClass)
        // thisAlias    the alias for arg1
        // thatAlias    the alias for arg2
        String retval = (cc.isNotContains() ? "( NOT (" : "(");
        boolean needComma = false;
        if (arg1 instanceof QueryCollectionReference) {
            CollectionDescriptor arg1Desc = arg1Class.getCollectionDescriptorByName(
                    arg1.getFieldName());
            if (arg1Desc == null) {
                throw (new IllegalArgumentException("Couldn't find CollectionDescriptor for "
                            + arg1.getQueryClass().getType().getName() + "."
                            + arg1.getFieldName()));
            }
            // Now arg1Desc is the CollectionDescriptor for arg1 (the QueryReference).
            if (arg1Desc.isMtoNRelation()) {
                // M to N relation
                FieldDescriptor thisFields[] = arg1Class.getPkFields();
                if (thisFields == null) {
                    throw (new IllegalArgumentException("Couldn't find primary key array for "
                                + arg1.getQueryClass().getType().toString()));
                }
                String thisIntermediateFields[] = arg1Desc.getFksToThisClass();
                if (thisFields.length != thisIntermediateFields.length) {
                    throw (new IllegalArgumentException("Field arrays for foreign and primary "
                                + "keys do not have equal length"));
                }
                String thatIntermediateFields[] = arg1Desc.getFksToItemClass();
                FieldDescriptor thatFields[] = arg2Class.getPkFields();
                if (thatFields == null) {
                    throw (new IllegalArgumentException("Couldn't find primary key array for "
                                + arg1.getQueryClass().getType().toString()));
                }
                if (thatFields.length != thatIntermediateFields.length) {
                    throw (new IllegalArgumentException("Field arrays for foreign and primary "
                                + "keys do not have equal length"));
                }
                String indirectionTable = arg1Desc.getIndirectionTable();
                // Now, thisFields is an array of fields in the arg1Class that should match
                // against the fields in thisIntermediateFields in the table indirectionTable.
                // thatFields is an array of fields in the arg2Class that should match against
                // the fields in thatIntermediateFields in the table indirectionTable.
                // The indirectionTable should be added to the FROM list, aliased. The alias
                // should be a composite of the aliases of both of the end-point tables, and
                // the name of the table.
                String indirectAlias = "ind_" + thisAlias + thatAlias + indirectionTable + "_";
                indirectionTables.put(indirectAlias, indirectionTable);
                for (int i = 0; i < thisFields.length; i++) {
                    if (needComma) {
                        retval += " AND ";
                    }
                    needComma = true;
                    retval += thisAlias + thisFields[i].getColumnName() + " = " + indirectAlias
                        + "." + thisIntermediateFields[i];
                }
                for (int i = 0; i < thatFields.length; i++) {
                    if (needComma) {
                        retval += " AND ";
                    }
                    needComma = true;
                    retval += thatAlias + thatFields[i].getColumnName() + " = " + indirectAlias
                        + "." + thatIntermediateFields[i];
                }
            } else {
                // 1 to N relation
                FieldDescriptor thisFields[] = arg1Class.getPkFields();
                if (thisFields == null) {
                    throw (new IllegalArgumentException("Couldn't find primary key array for "
                                + arg1.getQueryClass().getType().toString()));
                }
                FieldDescriptor thatFields[] =
                    arg1Desc.getForeignKeyFieldDescriptors(arg2Class);
                if (thatFields == null) {
                    throw (new IllegalArgumentException("Couldn't find foreign key array for "
                                + arg1.getQueryClass().getType().getName() + "."
                                + arg1.getFieldName()));
                }
                if (thisFields.length != thatFields.length) {
                    throw (new IllegalArgumentException("Field arrays for foreign and primary "
                                + "keys do not have equal length"));
                }
                // Now, thisFields is an array of fields in the arg1Class that should match
                // against the fields in thatFields (which are fields of arg2Class).
               for (int i = 0; i < thisFields.length; i++) {
                    FieldDescriptor thisField = thisFields[i];
                    FieldDescriptor thatField = thatFields[i];
                    if (needComma) {
                        retval += " AND ";
                    }
                    needComma = true;
                    retval += thisAlias + thisField.getColumnName() + " = " + thatAlias
                        + thatField.getColumnName();
                }
            }
        } else if (arg1 instanceof QueryObjectReference) {
            ObjectReferenceDescriptor arg1Desc = arg1Class.getObjectReferenceDescriptorByName(
                    arg1.getFieldName());
            if (arg1Desc == null) {
                throw (new IllegalArgumentException("Couldn't find object descriptor for "
                            + arg1.getQueryClass().getType().getName() + "."
                            + arg1.getFieldName()));
            }
            FieldDescriptor thisFields[] = arg1Desc.getForeignKeyFieldDescriptors(arg1Class);
            if (thisFields == null) {
                throw (new IllegalArgumentException("Couldn't find foreign key array for "
                            + arg1.getQueryClass().getType().getName() + "."
                            + arg1.getFieldName()));
            }
            FieldDescriptor thatFields[] = arg2Class.getPkFields();
            if (thatFields == null) {
                throw (new IllegalArgumentException("Couldn't find primary key array for "
                            + arg2.getType()));
            }
            if (thisFields.length != thatFields.length) {
                throw (new IllegalArgumentException("Field arrays for foreign and primary keys "
                            + "do not have equal length"));
            }
            // Now, thisFields is an array of fields in the arg1Class that should match against
            // the fields in thatFields (which are fields of arg2Class).
            for (int i = 0; i < thisFields.length; i++) {
                FieldDescriptor thisField = thisFields[i];
                FieldDescriptor thatField = thatFields[i];
                if (needComma) {
                    retval += " AND ";
                }
                needComma = true;
                retval += thisAlias + thisField.getColumnName() + " = " + thatAlias
                    + thatField.getColumnName();
            }
        }
        return retval + (cc.isNotContains() ? "))" : ")");
    }

    /**
     * Returns the GROUP BY clause for the SQL query.
     *
     * @return the SQL GROUP BY clause
     */
    protected String buildGroupBy() {
        String retval = "";
        boolean needComma = false;
        Set groupBy = query.getGroupBy();
        Iterator groupByIter = groupBy.iterator();
        while (groupByIter.hasNext()) {
            QueryNode node = (QueryNode) groupByIter.next();
            retval += (needComma ? ", " : " GROUP BY ");
            needComma = true;
            if (node instanceof QueryClass) {
                retval += queryClassToString((QueryClass) node, false, false);
            } else {
                retval += queryEvaluableToString((QueryEvaluable) node);
            }
        }
        return retval;
    }

    /**
     * Returns the ORDER BY clause for the SQL query.
     *
     * @return the SQL ORDER BY clause
     */
    protected String buildOrderBy() {
        String retval = "";
        boolean needComma = false;
        List orderBy = query.getOrderBy();
        Iterator orderByIter = orderBy.iterator();
        while (orderByIter.hasNext()) {
            QueryNode node = (QueryNode) orderByIter.next();
            retval += (needComma ? ", " : " ORDER BY ");
            needComma = true;
            if (node instanceof QueryClass) {
                retval += queryClassToString((QueryClass) node, false, true);
            } else {
                retval += queryEvaluableToString((QueryEvaluable) node);
            }
        }
        List select = query.getSelect();
        Iterator selectIter = select.iterator();
        while (selectIter.hasNext()) {
            QueryNode node = (QueryNode) selectIter.next();
            retval += (needComma ? ", " : " ORDER BY ");
            needComma = true;
            if (node instanceof QueryClass) {
                retval += queryClassToString((QueryClass) node, false, true);
            } else if (node instanceof QueryValue) {
                // Do nothing
                retval = retval;
            } else {
                retval += queryEvaluableToString((QueryEvaluable) node);
            }
        }
        return retval;
    }

    /**
     * Return the statement as a string
     *
     * @return sql statement as a string
     */
    public String getStatement() {
        needWhereComma = false;
        whereText = "";
        needFromComma = false;
        fromText = "";
        classToFieldNames = new HashMap();
        indirectionTables = new HashMap();
        buildFromComponent();
        buildWhereClause();
        return "SELECT " + (query.isDistinct() ? "DISTINCT " : "") + buildSelectComponent()
            + " FROM " + fromText + whereText + buildGroupBy()
            + (isSubQuery ? "" : buildOrderBy() + " LIMIT " + limit + " OFFSET " + start);
    }

}
