/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v3_2.planner

import org.neo4j.cypher.internal.compiler.v3_2._
import org.neo4j.cypher.internal.compiler.v3_2.ast.rewriters._
import org.neo4j.cypher.internal.compiler.v3_2.phases._
import org.neo4j.cypher.internal.compiler.v3_2.planner.logical.Metrics._
import org.neo4j.cypher.internal.compiler.v3_2.planner.logical.cardinality.QueryGraphCardinalityModel
import org.neo4j.cypher.internal.compiler.v3_2.planner.logical.idp.{IDPQueryGraphSolver, IDPQueryGraphSolverMonitor, SingleComponentPlanner, cartesianProductsOrValueJoins}
import org.neo4j.cypher.internal.compiler.v3_2.planner.logical.plans._
import org.neo4j.cypher.internal.compiler.v3_2.planner.logical.plans.rewriter.unnestApply
import org.neo4j.cypher.internal.compiler.v3_2.planner.logical.steps.LogicalPlanProducer
import org.neo4j.cypher.internal.compiler.v3_2.planner.logical.{LogicalPlanningContext, _}
import org.neo4j.cypher.internal.compiler.v3_2.spi._
import org.neo4j.cypher.internal.compiler.v3_2.test_helpers.ContextHelper
import org.neo4j.cypher.internal.compiler.v3_2.tracing.rewriters.RewriterStepSequencer
import org.neo4j.cypher.internal.compiler.v3_2.tracing.rewriters.RewriterStepSequencer.newPlain
import org.neo4j.cypher.internal.frontend.v3_2.ast._
import org.neo4j.cypher.internal.frontend.v3_2.helpers.fixedPoint
import org.neo4j.cypher.internal.frontend.v3_2.parser.CypherParser
import org.neo4j.cypher.internal.frontend.v3_2.test_helpers.{CypherFunSuite, CypherTestSupport}
import org.neo4j.cypher.internal.frontend.v3_2.{Foldable, PropertyKeyId, SemanticTable}
import org.neo4j.cypher.internal.ir.v3_2.{Cardinality, IdName, PeriodicCommit}
import org.neo4j.helpers.collection.Visitable
import org.neo4j.kernel.api.constraints.UniquenessConstraint
import org.neo4j.kernel.api.index.IndexDescriptor
import org.neo4j.kernel.impl.util.dbstructure.DbStructureVisitor
import org.scalatest.matchers.{BeMatcher, MatchResult}

import scala.language.reflectiveCalls
import scala.reflect.ClassTag

trait LogicalPlanningTestSupport2 extends CypherTestSupport with AstConstructionTestSupport with LogicalPlanConstructionTestSupport {
  self: CypherFunSuite =>

  val solved = CardinalityEstimation.lift(PlannerQuery.empty, Cardinality(0))
  var parser = new CypherParser
  val rewriterSequencer = RewriterStepSequencer.newValidating _
  var astRewriter = new ASTRewriter(rewriterSequencer, shouldExtractParameters = false)
  final var planner = new QueryPlanner() {
    def internalPlan(query: PlannerQuery)(implicit context: LogicalPlanningContext, leafPlan: Option[LogicalPlan] = None): LogicalPlan =
      planSingleQuery(query)
  }
  var queryGraphSolver: QueryGraphSolver = new IDPQueryGraphSolver(SingleComponentPlanner(mock[IDPQueryGraphSolverMonitor]), cartesianProductsOrValueJoins, mock[IDPQueryGraphSolverMonitor])
  val realConfig = new RealLogicalPlanningConfiguration

  def solvedWithEstimation(cardinality: Cardinality) = CardinalityEstimation.lift(PlannerQuery.empty, cardinality)

  implicit class LogicalPlanningEnvironment[C <: LogicalPlanningConfiguration](config: C) {
    lazy val semanticTable = config.updateSemanticTableWithTokens(SemanticTable())

    def metricsFactory = new MetricsFactory {
      def newCostModel() =
        (plan: LogicalPlan, input: QueryGraphSolverInput) => config.costModel()(plan -> input)

      def newCardinalityEstimator(queryGraphCardinalityModel: QueryGraphCardinalityModel) =
        config.cardinalityModel(queryGraphCardinalityModel)

      def newQueryGraphCardinalityModel(statistics: GraphStatistics) = QueryGraphCardinalityModel.default(statistics)
    }

    def table = Map.empty[PatternExpression, QueryGraph]

    def planContext = new NotImplementedPlanContext {
      override def statistics: GraphStatistics =
        config.graphStatistics

      override def getUniqueIndexRule(labelName: String, propertyKey: String): Option[IndexDescriptor] =
        if (config.uniqueIndexes((labelName, propertyKey)))
          Some(new IndexDescriptor(
            semanticTable.resolvedLabelIds(labelName).id,
            semanticTable.resolvedPropertyKeyNames(propertyKey).id
          ))
        else
          None

      override def getUniquenessConstraint(labelName: String, propertyKey: String): Option[UniquenessConstraint] = {
        if (config.uniqueIndexes((labelName, propertyKey)))
          Some(new UniquenessConstraint(
            semanticTable.resolvedLabelIds(labelName).id,
            semanticTable.resolvedPropertyKeyNames(propertyKey).id
          ))
        else
          None
      }

      override def getIndexRule(labelName: String, propertyKey: String): Option[IndexDescriptor] =
        if (config.indexes((labelName, propertyKey)) || config.uniqueIndexes((labelName, propertyKey)))
          Some(new IndexDescriptor(
            semanticTable.resolvedLabelIds(labelName).id,
            semanticTable.resolvedPropertyKeyNames(propertyKey).id
          ))
        else
          None

      override def hasIndexRule(labelName: String): Boolean =
        config.indexes.exists(_._1 == labelName) || config.uniqueIndexes.exists(_._1 == labelName)

      override def getOptPropertyKeyId(propertyKeyName: String) =
        semanticTable.resolvedPropertyKeyNames.get(propertyKeyName).map(_.id)

      override def getOptLabelId(labelName: String): Option[Int] =
        semanticTable.resolvedLabelIds.get(labelName).map(_.id)

      override def getOptRelTypeId(relType: String): Option[Int] =
        semanticTable.resolvedRelTypeNames.get(relType).map(_.id)
    }

    val pipeLine =
      Parsing andThen
      PreparatoryRewriting andThen
      SemanticAnalysis(warn = true) andThen
      AstRewriting(newPlain, shouldExtractParams = false) andThen
      RewriteProcedureCalls andThen
      Namespacer andThen
      rewriteEqualityToInPredicate andThen
      CNFNormalizer andThen
      LateAstRewriting andThen
      ResolveTokens andThen
      CreatePlannerQuery andThen
      OptionalMatchRemover andThen
      QueryPlanner().adds[LogicalPlan] andThen
      Do(removeApply _)

    // This fakes pattern expression naming for testing purposes
    // In the actual code path, this renaming happens as part of planning
    //
    // cf. QueryPlanningStrategy
    //
    private def namePatternPredicates(input: CompilationState, context: Context): CompilationState = {
      val newStatement = input.statement.endoRewrite(namePatternPredicatePatternElements)
      input.copy(maybeStatement = Some(newStatement))
    }

    private def removeApply(input: CompilationState, context: Context): CompilationState = {
      val newPlan = input.logicalPlan.endoRewrite(fixedPoint(unnestApply))
      input.copy(maybeLogicalPlan = Some(newPlan))
    }

    def getLogicalPlanFor(queryString: String): (Option[PeriodicCommit], LogicalPlan, SemanticTable) = {
      val mkException = new SyntaxExceptionCreator(queryString, Some(pos))
      val metrics = metricsFactory.newMetrics(planContext.statistics)
      def context = ContextHelper.create(planContext = planContext, exceptionCreator = mkException, queryGraphSolver = queryGraphSolver, metrics = metrics)

      val state = CompilationState(queryString, None, IDPPlannerName)
      val output = pipeLine.transform(state, context)
      val logicalPlan = output.logicalPlan.asInstanceOf[ProduceResult].inner
      (output.periodicCommit, logicalPlan, output.semanticTable)
    }

    def estimate(qg: QueryGraph, input: QueryGraphSolverInput = QueryGraphSolverInput.empty) =
      metricsFactory.newMetrics(config.graphStatistics).queryGraphCardinalityModel(qg, input, semanticTable)

    def withLogicalPlanningContext[T](f: (C, LogicalPlanningContext) => T): T = {
      val metrics = metricsFactory.newMetrics(config.graphStatistics)
      val logicalPlanProducer = LogicalPlanProducer(metrics.cardinality)
      val ctx = LogicalPlanningContext(
        planContext = planContext,
        logicalPlanProducer = logicalPlanProducer,
        metrics = metrics,
        semanticTable = semanticTable,
        strategy = queryGraphSolver,
        input = QueryGraphSolverInput.empty,
        notificationLogger = devNullLogger
      )
      f(config, ctx)
    }
  }

  def fakeLogicalPlanFor(id: String*): FakePlan = FakePlan(id.map(IdName(_)).toSet)(solved)

  def planFor(queryString: String): (Option[PeriodicCommit], LogicalPlan, SemanticTable) =
    new given().getLogicalPlanFor(queryString)

  class given extends StubbedLogicalPlanningConfiguration(realConfig)

  class fromDbStructure(dbStructure: Visitable[DbStructureVisitor])
    extends DelegatingLogicalPlanningConfiguration(DbStructureLogicalPlanningConfiguration(dbStructure))

  implicit def propertyKeyId(label: String)(implicit semanticTable: SemanticTable): PropertyKeyId =
    semanticTable.resolvedPropertyKeyNames(label)

  def using[T <: LogicalPlan](implicit tag: ClassTag[T]): BeMatcher[LogicalPlan] = new BeMatcher[LogicalPlan] {
    import Foldable._
    override def apply(actual: LogicalPlan): MatchResult = {
      val matches = actual.treeFold(false) {
        case lp if tag.runtimeClass.isInstance(lp) => acc => (true, None)
      }
      MatchResult(
        matches = matches,
        rawFailureMessage = s"Plan should use ${tag.runtimeClass.getSimpleName}",
        rawNegatedFailureMessage = s"Plan should not use ${tag.runtimeClass.getSimpleName}")
    }
  }
}
