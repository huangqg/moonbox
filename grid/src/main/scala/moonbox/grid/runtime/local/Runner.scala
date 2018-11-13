/*-
 * <<
 * Moonbox
 * ==
 * Copyright (C) 2016 - 2018 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */

package moonbox.grid.runtime.local

import java.util.concurrent.Executors

import akka.actor.{Actor, ActorRef, PoisonPill}
import akka.pattern._
import akka.util.Timeout
import moonbox.common.{MbConf, MbLogging}
import moonbox.core.command._
import moonbox.core.config.CACHE_IMPLEMENTATION
import moonbox.core.datasys.{DataSystem, Insertable}
import moonbox.core.{ColumnSelectPrivilegeException, MbSession, TableInsertPrivilegeChecker, TableInsertPrivilegeException}
import moonbox.grid.deploy2.node.ScheduleMessage._
import moonbox.grid.timer.{EventCall, EventEntity}
import moonbox.protocol.app.JobState.JobState
import moonbox.protocol.app._
import org.apache.spark.sql.{Row, SaveMode}
import org.apache.spark.sql.optimizer.WholePushdown

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

class Runner(conf: MbConf, mbSession: MbSession) extends Actor with MbLogging {
	implicit val askTimeout = Timeout(new FiniteDuration(20, SECONDS))
	private val awaitTimeout = new FiniteDuration(20, SECONDS)
	private implicit val catalogSession = mbSession.userContext
	private var currentJob: CommandInfo = _
	private val resultSchemaHashMap = mutable.HashMap.empty[String, String]
	private val resultDataHashMap = mutable.HashMap.empty[String, Iterator[Seq[String]]]

    private implicit val contextExecutor = {
        val executor = Executors.newFixedThreadPool(10)  //poolsize is temporarily set 10
        ExecutionContext.fromExecutor(executor)
    }

	override def receive: Receive = {
		case RunCommand(cmdInfo) =>
			logInfo(s"Runner::RunCommand  $cmdInfo")
			currentJob = cmdInfo
			val target = sender()
			run(cmdInfo, target).onComplete {
				case Success(data) =>
					successCallback(cmdInfo.jobId, cmdInfo.seq, data, target, cmdInfo.sessionId.isEmpty)
				case Failure(e) =>
                    if(e.getMessage != null && e.getMessage.contains("cancelled job")){
                        cancelCallback(cmdInfo.jobId, cmdInfo.seq, e, target, false) //TaskKilledException can not catch
                    }else{
                        failureCallback(cmdInfo.jobId, cmdInfo.seq, e, target, cmdInfo.sessionId.isEmpty)
                    }
			}
		case CancelJob(jobId) =>
      logInfo(s"Runner::CancelJob [WARNING] !!! $jobId")
			/* for batch */
			mbSession.cancelJob(jobId)
			if (currentJob.sessionId.isDefined && currentJob.sessionId.get == jobId){
				/* for adhoc */
				mbSession.cancelJob(currentJob.jobId)
			}
		case KillRunner =>
			logInfo(s"Runner::KillRunner $currentJob")
			if(currentJob == null || currentJob.sessionId.isDefined) {  //if a runner have not a job OR it is an adhoc, release resources
				clean(JobState.KILLED)
				self ! PoisonPill
			}

		case FetchDataFromRunner(_, jobId, fetchSize) =>
			val target = sender()
			Future {
				val directData = fetchData(jobId, fetchSize)
				target ! FetchedDataFromRunner(jobId, directData.schema, directData.data, directData.hasNext)
			}.onComplete {
				case Success(_) =>
				case Failure(e) => target ! FetchDataFromRunnerFailed(jobId, e.getMessage)
			}

	}

	def run(cmdInfo: CommandInfo, target: ActorRef): Future[JobResult] = {
		Future {
			val cmd = cmdInfo.command
			mbSession.withPrivilege(cmd) {
				cmd match {
					case event: CreateTimedEvent =>
						createTimedEvent(event, target)
					case event: AlterTimedEventSetEnable =>
						alterTimedEvent(event, target)
					case runnable: MbRunnableCommand =>
						val row: Seq[Row] = runnable.run(mbSession)
						DirectData(cmdInfo.jobId, runnable.outputSchema, row.map(_.toSeq.map(_.toString)), false)
					case tempView: CreateTempView =>
						createTempView(tempView)
					case query: MQLQuery =>
						mqlQuery(query, cmdInfo.jobId)
					case insert: InsertInto =>
						insertInto(insert)
					case _ => throw new Exception("Unsupported command.")
				}
			}
		}
	}


	def fetchData(jobId: String, fetchSize: Long): DirectData = {
		if (resultSchemaHashMap.get(jobId).isDefined && resultDataHashMap.get(jobId).isDefined) {

			val schema = resultSchemaHashMap(jobId)
			val buffer: ArrayBuffer[Seq[String]] = ArrayBuffer.empty[Seq[String]]
			val iterator = resultDataHashMap(jobId)

			var startSize: Long = 0
			while (iterator.hasNext && startSize < fetchSize) {
				buffer += iterator.next()
				startSize += 1
			}

			if (!iterator.hasNext) {
				logInfo(s"remove jobId from result hashMap $jobId")
				resultDataHashMap.remove(jobId)
				resultSchemaHashMap.remove(jobId)
				DirectData(jobId, schema, buffer, false)
			} else {
				DirectData(jobId, schema, buffer, true)
			}
		}
		else {
			DirectData(jobId, "", Seq.empty[Seq[String]], false)
		}
	}

	def createTimedEvent(event: CreateTimedEvent, target: ActorRef): JobResult = {
		val result = if (event.enable) {
			val catalogProcedure = mbSession.catalog.getProcedure(catalogSession.organizationId, event.proc)
			val definer = event.definer.getOrElse(catalogSession.userName)
			val sqls = catalogProcedure.cmds
			val config = catalogProcedure.config
			val eventEntity = EventEntity(
				group = catalogSession.organizationName,
				name = event.name,
				sqls = sqls,
				config,
				cronExpr = event.schedule,
				definer = definer,
				start = None,
				end = None,
				desc = event.description,
				function = new EventCall(definer, sqls, config)
			)
			val response = target.ask(RegisterTimedEvent(eventEntity)).mapTo[RegisterTimedEventResponse].flatMap {
				case RegisteredTimedEvent =>
					Future(event.run(mbSession).map(_.toSeq.map(_.toString)))
				case RegisterTimedEventFailed(message) =>
					throw new Exception(message)
			}
			Await.result(response, awaitTimeout)
		} else {
			event.run(mbSession).map(_.toSeq.map(_.toString))
		}
		//DirectData(result)
		UnitData
	}

	def alterTimedEvent(event: AlterTimedEventSetEnable, target: ActorRef): JobResult = {
		val result = if (event.enable) {
			val existsEvent = mbSession.catalog.getTimedEvent(catalogSession.organizationId, event.name)
			val catalogUser = mbSession.catalog.getUser(existsEvent.definer)
			val catalogProcedure = mbSession.catalog.getProcedure(existsEvent.procedure)
			val eventEntity = EventEntity(
				group = catalogSession.organizationName,
				name = event.name,
				sqls = catalogProcedure.cmds,
				config = catalogProcedure.config,
				cronExpr = existsEvent.schedule,
				definer = catalogUser.name,
				start = None,
				end = None,
				desc = existsEvent.description,
				function = new EventCall(catalogUser.name, catalogProcedure.cmds, catalogProcedure.config)
			)
			target.ask(RegisterTimedEvent(eventEntity)).mapTo[RegisterTimedEventResponse].flatMap {
				case RegisteredTimedEvent =>
					Future(event.run(mbSession).map(_.toSeq.map(_.toString)))
				case RegisterTimedEventFailed(message) =>
					throw new Exception(message)
			}
		} else {
			target.ask(UnregisterTimedEvent(catalogSession.organizationName, event.name))
				.mapTo[UnregisterTimedEventResponse].flatMap {
				case UnregisteredTimedEvent =>
					Future(event.run(mbSession).map(_.toSeq.map(_.toString)))
				case UnregisterTimedEventFailed(message) =>
					throw new Exception(message)
			}
		}
		Await.result(result, awaitTimeout)
		UnitData
	}

	def createTempView(tempView: CreateTempView): JobResult = {
		val optimized = mbSession.optimizedPlan(tempView.query)
		val plan = mbSession.pushdownPlan(optimized, pushdown = false)
		val df = mbSession.toDF(plan)
		if (tempView.isCache) {
			df.cache()
		}
		if (tempView.replaceIfExists) {
			df.createOrReplaceTempView(tempView.name)
		} else {
			df.createTempView(tempView.name)
		}
		UnitData
	}

	def mqlQuery(query: MQLQuery, jobId: String): JobResult = {
		val format = conf.get(CACHE_IMPLEMENTATION.key, CACHE_IMPLEMENTATION.defaultValueString)
		var iter: scala.Iterator[Row] = null
		val options = conf.getAll.filterKeys(_.startsWith("moonbox.cache")).+("jobId" -> jobId)
		val optimized = mbSession.optimizedPlan(query.query)
		try {
            mbSession.mixcal.setJobGroup(jobId)  //cancel
			val plan = mbSession.pushdownPlan(optimized)
			plan match {
				case WholePushdown(child, queryable) =>
                    logInfo(s"WholePushdown $query")
					//mbSession.toDT(child, queryable).write().format(format).options(options).save()
					iter = mbSession.toDT(child, queryable).iter
				case _ =>
					//mbSession.toDF(plan).write.format(format).options(options).save()
					iter = mbSession.toDF(plan).collect().iterator
			}
		} catch {
			case e: ColumnSelectPrivilegeException =>
				throw e
			case e: Throwable =>
                if (e.getMessage.contains("cancelled job")) {
                    throw e
                } else {
                    e.printStackTrace()
                    logWarning(s"Execute push failed with ${e.getMessage}. Retry without pushdown.")
                    val plan = mbSession.pushdownPlan(optimized, pushdown = false)
                    plan match {
                        case WholePushdown(child, queryable) =>
                            //mbSession.toDF(child).write.format(format).options(options).save()
							iter = mbSession.toDT(child, queryable).iter
                        case _ =>
                            //mbSession.toDF(plan).write.format(format).options(options).save()
							iter = mbSession.toDF(plan).collect().iterator
                    }
                }
		}
		resultSchemaHashMap.put(jobId, optimized.schema.json) //save schema

		val buffer: ArrayBuffer[Seq[String]] = ArrayBuffer.empty[Seq[String]]
		while (iter.hasNext) {
			buffer += iter.next().toSeq.map { elem =>
				if ( elem == null) { "" }
				else { elem.toString }
			}
		}
		resultDataHashMap.put(jobId, buffer.iterator)  //save data

		fetchData(jobId, 200)

	}

	def insertInto(insert: InsertInto): JobResult = {
		// TODO write privilege
		val sinkCatalogTable = mbSession.getCatalogTable(insert.table.table, insert.table.database)
		val options = sinkCatalogTable.properties
		val sinkDataSystem = DataSystem.lookupDataSystem(options)
		val format = DataSystem.lookupDataSource(options("type"))
		val saveMode = if (insert.overwrite) SaveMode.Overwrite else SaveMode.Append
		val optimized = mbSession.optimizedPlan(insert.query)
		try {
			val plan = mbSession.pushdownPlan(optimized)
			plan match {
				case WholePushdown(child, queryable) if sinkDataSystem.isInstanceOf[Insertable] =>
					val dataTable = mbSession.toDT(child, queryable)
					TableInsertPrivilegeChecker.intercept(mbSession, sinkCatalogTable, dataTable)
					.write().format(format).options(options).mode(saveMode).save()
				case _ =>
					val dataFrame = mbSession.toDF(plan)
					TableInsertPrivilegeChecker.intercept(mbSession, sinkCatalogTable, dataFrame).write.format(format).options(options).mode(saveMode).save()
			}
		} catch {
			case e: ColumnSelectPrivilegeException =>
				throw e
            case e: TableInsertPrivilegeException =>
                throw e
			case e: Throwable =>
				logWarning(e.getMessage)
				val plan = mbSession.pushdownPlan(optimized, pushdown = false)
				plan match {
					case WholePushdown(child, queryable) =>
						mbSession.toDF(child).write.format(format).options(options).mode(saveMode).save()
					case _ =>
						mbSession.toDF(plan).write.format(format).options(options).mode(saveMode).save()
				}
		}
		UnitData
	}

	private def clean(jobState: JobState): Unit = {
		Future {
			logInfo(s"Runner::clean $jobState start")
			mbSession.cancelJob(currentJob.jobId)
			// session.mixcal.sparkSession.sessionState.catalog.reset()
			mbSession.catalog.stop()
			logInfo(s"Runner::clean $jobState end")
		}
	}

	private def successCallback(jobId: String, seq: Int, result: JobResult, requester: ActorRef, shutdown: Boolean): Unit = {
		requester ! JobStateChanged(jobId, seq, JobState.SUCCESS, result)
		if (shutdown) {
			clean(JobState.SUCCESS)
			self ! PoisonPill
		}
	}

	private def failureCallback(jobId: String, seq: Int, e: Throwable, requester: ActorRef, shutdown: Boolean): Unit = {
		logError(e.getStackTrace.map(_.toString).mkString("\n"))
		requester ! JobStateChanged(jobId, seq, JobState.FAILED, Failed(e.getMessage))
		if (shutdown) {
			clean(JobState.FAILED)
			self ! PoisonPill
		}
	}

    private def cancelCallback(jobId: String, seq: Int, e: Throwable, requester: ActorRef, shutdown: Boolean): Unit = {
        logWarning(e.getStackTrace.map(_.toString).mkString("\n"))
        requester ! JobStateChanged(jobId, seq, JobState.KILLED, Failed(e.getMessage))
        if (shutdown) {
            clean(JobState.KILLED)
            self ! PoisonPill
        }
    }

}