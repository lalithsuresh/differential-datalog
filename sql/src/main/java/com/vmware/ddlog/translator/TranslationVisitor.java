/*
 * Copyright (c) 2019 VMware Inc. All Rights Reserved.
 * SPDX-License-Identifier: MIT
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice (including the next paragraph) shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.vmware.ddlog.translator;

import com.facebook.presto.sql.tree.*;
import com.vmware.ddlog.ir.*;

import java.util.ArrayList;
import java.util.List;

class TranslationVisitor extends AstVisitor<DDlogIRNode, TranslationContext> {
    static String convertQualifiedName(QualifiedName name) {
        return String.join(".", name.getParts());
    }

    @Override
    protected DDlogIRNode visitCreateTable(CreateTable node, TranslationContext context) {
        String name = convertQualifiedName(node.getName());

        List<TableElement> elements = node.getElements();
        List<DDlogField> fields = new ArrayList<DDlogField>();
        for (TableElement te: elements) {
            DDlogIRNode field = this.process(te, context);
            if (field instanceof DDlogField)
                fields.add((DDlogField)field);
            else
                throw new TranslationException("Unexpected table element", te);
        }
        DDlogTStruct type = new DDlogTStruct(DDlogType.typeName(name), fields);
        DDlogTypeDef tdef = new DDlogTypeDef(type.getName(), type);
        context.add(tdef);
        DDlogRelation rel = new DDlogRelation(
                DDlogRelation.RelationRole.RelInput, name, new DDlogTUser(tdef.getName()));
        context.add(rel);
        return rel;
    }

    @Override
    protected DDlogIRNode visitTableSubquery(TableSubquery query, TranslationContext context) {
        DDlogIRNode subquery = this.process(query.getQuery(), context);
        if (!(subquery instanceof RelationRHS))
            throw new TranslationException(
                    "Translating query specification did not produce a relation: " +
                            subquery.getClass(), query);
        RelationRHS relation = (RelationRHS)subquery;

        String relName = context.freshGlobalName("tmp");
        DDlogRelation rel = new DDlogRelation(
                DDlogRelation.RelationRole.RelInternal, relName, relation.getType());
        context.add(rel);
        String lhsVar = context.freshLocalName("v");
        DDlogExpression varExpr = new DDlogEVar(lhsVar, relation.getType());
        List<DDlogRuleRHS> rhs = relation.getDefinitions();
        DDlogESet set = new DDlogESet(new DDlogEVarDecl(lhsVar, relation.getType()),
                relation.getRowVariable(false));
        rhs.add(new DDlogRHSCondition(set));
        DDlogAtom lhs = new DDlogAtom(relName, varExpr);
        DDlogRule rule = new DDlogRule(lhs, rhs);
        context.add(rule);
        RelationRHS result = new RelationRHS(lhsVar, relation.getType());
        result.addDefinition(new DDlogRHSLiteral(true, new DDlogAtom(rel.getName(), varExpr)));
        TranslationContext.Scope scope = new TranslationContext.Scope(lhsVar, rel.getType());
        context.enterScope(scope);
        return result;
    }

    @Override
    protected DDlogIRNode visitQuery(Query query, TranslationContext context) {
        if (query.getLimit().isPresent())
            throw new TranslationException("LIMIT clauses not supported", query);
        if (query.getOrderBy().isPresent())
            throw new TranslationException("ORDER BY clauses not supported", query);
        if (query.getWith().isPresent())
            throw new TranslationException("WITH clauses not supported", query);
        return this.process(query.getQueryBody(), context);
    }

    @Override
    protected DDlogIRNode visitTable(Table table, TranslationContext context) {
        String name = convertQualifiedName(table.getName());
        DDlogRelation relation = context.getRelation(name);
        if (relation == null)
            throw new TranslationException("Could not find relation", table);
        String var = context.freshLocalName("v");
        DDlogType type = relation.getType();
        TranslationContext.Scope scope = new TranslationContext.Scope(var, type);
        context.enterScope(scope);
        RelationRHS result = new RelationRHS(var, type);
        result.addDefinition(new DDlogRHSLiteral(
                true, new DDlogAtom(relation.getName(), result.getRowVariable(false))));
        return result;
    }

    private DDlogIRNode processSelect(RelationRHS relation, Select select, TranslationContext context) {
        if (!select.isDistinct())
            throw new TranslationException("Only SELECT DISTINCT currently supported", select);

        // Special case for SELECT *
        List<SelectItem> items = select.getSelectItems();
        if (items.size() == 1) {
            SelectItem single = items.get(0);
            if (single instanceof AllColumns) {
                AllColumns all = (AllColumns)single;
                if (!all.getPrefix().isPresent())
                    return relation;
            }
        }
        String outRelName = context.freshGlobalName("tmp");
        List<DDlogField> typeList = new ArrayList<DDlogField>();
        List<DDlogEStruct.FieldValue> exprList = new ArrayList<DDlogEStruct.FieldValue>();
        for (SelectItem s: items) {
            if (s instanceof AllColumns) {
                AllColumns ac = (AllColumns)s;
                // TODO
            } else {
                SingleColumn sc = (SingleColumn)s;
                String name;
                if (sc.getAlias().isPresent())
                    name = sc.getAlias().get().getValue();
                else {
                    ExpressionColumnName ecn = new ExpressionColumnName();
                    name = ecn.process(sc.getExpression());
                    if (name == null)
                        name = context.freshLocalName("col");
                }
                DDlogExpression expr = context.translateExpression(sc.getExpression());
                typeList.add(new DDlogField(name, expr.getType()));
                exprList.add(new DDlogEStruct.FieldValue(name, expr));
            }
        }
        String newTypeName = DDlogType.typeName(outRelName);
        DDlogTStruct type = new DDlogTStruct(newTypeName, typeList);
        DDlogTypeDef tdef = new DDlogTypeDef(newTypeName, type);
        context.add(tdef);
        DDlogType tuser = new DDlogTUser(tdef.getName());
        DDlogExpression project = new DDlogEStruct(newTypeName, exprList, tuser);
        DDlogRelation outRel = new DDlogRelation(DDlogRelation.RelationRole.RelInternal, outRelName, tuser);
        context.add(outRel);

        String var = context.freshLocalName("v");
        RelationRHS result = new RelationRHS(var, tuser);
        for (DDlogRuleRHS rhs: relation.getDefinitions())
            result.addDefinition(rhs);
        DDlogExpression assignProject = new DDlogESet(
                result.getRowVariable(true),
                project);
        return result.addDefinition(assignProject);
    }

    @Override
    protected DDlogIRNode visitQuerySpecification(QuerySpecification spec, TranslationContext context) {
        if (spec.getLimit().isPresent())
            throw new TranslationException("LIMIT clauses not supported", spec);
        if (spec.getOrderBy().isPresent())
            throw new TranslationException("ORDER BY clauses not supported", spec);
        if (!spec.getFrom().isPresent())
            throw new TranslationException("FROM clause is required", spec);
        DDlogIRNode source = this.process(spec.getFrom().get(), context);
        if (source == null)
            throw new TranslationException("Not yet handled", spec);
        if (!(source instanceof RelationRHS))
            throw new RuntimeException(
                    "Translating query specification did not produce a relation: " +
                            source.getClass());
        RelationRHS relation = (RelationRHS)source;
        if (spec.getWhere().isPresent()) {
            Expression expr = spec.getWhere().get();
            DDlogExpression ddexpr = context.translateExpression(expr);
            relation = relation.addDefinition(ddexpr);
        }

        Select select = spec.getSelect();
        return this.processSelect(relation, select, context);
    }

    @Override
    protected DDlogIRNode visitCreateView(CreateView view, TranslationContext context) {
        String name = convertQualifiedName(view.getName());
        DDlogIRNode query = process(view.getQuery(), context);
        if (!(query instanceof RelationRHS))
            throw new TranslationException("Unexpected query translation", view);

        RelationRHS rel = (RelationRHS)query;
        DDlogRelation out = new DDlogRelation(
                DDlogRelation.RelationRole.RelOutput, name, rel.getType());

        String outVarName = context.freshLocalName("v");
        DDlogExpression outRowVarDecl = new DDlogEVarDecl(outVarName, rel.getType());
        DDlogExpression inRowVar = rel.getRowVariable(false);
        List<DDlogRuleRHS> rhs = rel.getDefinitions();
        DDlogESet set = new DDlogESet(outRowVarDecl, inRowVar);
        rhs.add(new DDlogRHSCondition(set));
        DDlogAtom lhs = new DDlogAtom(name, new DDlogEVar(outVarName, out.getType()));
        DDlogRule rule = new DDlogRule(lhs, rhs);
        context.add(out);
        context.add(rule);
        return rule;
    }

    @Override
    protected DDlogIRNode visitColumnDefinition(ColumnDefinition definition, TranslationContext context) {
        String name = definition.getName().getValue();
        String type = definition.getType();
        DDlogType ddtype = createType(type);
        return new DDlogField(name, ddtype);
    }

    private static DDlogType createType(String sqltype) {
        if (sqltype.equals("boolean"))
            return DDlogTBool.instance;
        else if (sqltype.equals("integer"))
            return new DDlogTSigned(64);
        else if (sqltype.startsWith("varchar"))
            return DDlogTString.instance;
        else if (sqltype.equals("bigint")) {
            return DDlogTInt.instance;
        }
        throw new RuntimeException("SQL type not yet implemented: " + sqltype);
    }
}
