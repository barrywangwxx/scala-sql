package wangzx.scala_commons.sql

import java.sql._
import scala.reflect.ClassTag
import scala.collection.mutable.ListBuffer

object RichConnection {
  val ClassOfResultSet = classOf[ResultSet]
  val ClassOfRow = classOf[Row]
  val ClassOfJdbcValueMapping = classOf[JdbcValueMapper[_]]

  import org.slf4j.{LoggerFactory, Logger}
  val LOG: Logger = LoggerFactory.getLogger(classOf[RichConnection])

}

class RichConnection(val conn: Connection)(val jdbcValueMapperFactory: JdbcValueMapperFactory) {

  import RichConnection._
  import BeanMapping._

  /**
   * map a ResultSet to an object, either ResultSet or Row or JavaBean
   */
  private def rs2mapped[T](rsMeta: ResultSetMetaData, rs: ResultSet, tag: ClassTag[T]): T = {
    tag.runtimeClass match {
      case ClassOfResultSet =>
        rs.asInstanceOf[T]
      case ClassOfRow =>
        Row.resultSetToRow(rsMeta, rs).asInstanceOf[T]
      case _ =>
        BeanMapping.rs2bean(rsMeta, rs, jdbcValueMapperFactory)(tag)
    }
  }

  def withStatement[T](f: Statement => T): T = {
    val stmt = conn.createStatement
    try {
      f(stmt)
    } finally {
      stmt.close()
    }
  }

  def withTransaction[T](f: Connection => T): T = {
    try {
      conn.setAutoCommit(false)
      val result = f(conn)
      conn.commit
      result
    } catch {
      case ex: Throwable =>
        conn.rollback
        throw ex
    }
  }

  def executeUpdate(stmt: SQLWithArgs): Int = executeUpdateWithGenerateKey(stmt)(null)

  @inline private def setStatementArgs(stmt: PreparedStatement, args: Seq[Any]) =
    args.zipWithIndex.foreach {
      case (v: JdbcValueMapper[AnyRef], idx) => stmt.setObject(idx+1, v.getJdbcValue(v))
      case (v, idx) if jdbcValueMapperFactory.getJdbcValueMapper(v.getClass) != null =>
        val mapper = jdbcValueMapperFactory.getJdbcValueMapper(v.getClass).asInstanceOf[JdbcValueMapper[Any]]
        stmt.setObject( idx+1, mapper.getJdbcValue(v) )
      case (v: BigDecimal, idx) => stmt.setBigDecimal(idx+1, v.bigDecimal)
      case (v, idx) => stmt.setObject(idx + 1, v)
    }

  def executeUpdateWithGenerateKey(stmt: SQLWithArgs)(processGenerateKeys: ResultSet => Unit = null): Int = {
    val prepared = conn.prepareStatement(stmt.sql,
      if (processGenerateKeys != null) Statement.RETURN_GENERATED_KEYS
      else Statement.NO_GENERATED_KEYS)

    if (stmt.args != null) setStatementArgs(prepared, stmt.args)

    LOG.debug("SQL Preparing: {} args: {}", Seq(stmt.sql, stmt.args): _*)

    val result = prepared.executeUpdate()

    if (processGenerateKeys != null) {
      val keys = prepared.getGeneratedKeys
      processGenerateKeys(keys)
    }

    LOG.debug("SQL result: {}", result)
    result
  }

  def eachRow[T : ClassTag](sql: SQLWithArgs)(f: T => Unit) {
    val prepared = conn.prepareStatement(sql.sql)
    if (sql.args != null) setStatementArgs(prepared, sql.args)

    LOG.debug("SQL Preparing: {} args: {}", Seq(sql.sql, sql.args):_*)

    val rs = prepared.executeQuery()
    val rsMeta = rs.getMetaData
    while (rs.next()) {
      val mapped = rs2mapped(rsMeta, rs, implicitly[ClassTag[T]])
      f(mapped)
    }
    LOG.debug("SQL result: {}", rs.getRow)
  }

  def rows[T : ClassTag](sql: SQLWithArgs): List[T] = {
    val buffer = new ListBuffer[T]()
    val prepared = conn.prepareStatement(sql.sql)
    if (sql.args != null) setStatementArgs(prepared, sql.args)

    LOG.debug("SQL Preparing: {} args: {}", Seq(sql.sql, sql.args):_*)

    val rs = prepared.executeQuery()
    val rsMeta = rs.getMetaData
    while (rs.next()) {
      val mapped = rs2mapped(rsMeta, rs, implicitly[ClassTag[T]])
      buffer += mapped

    }
    LOG.debug("SQL result: {}", buffer.size)
    buffer.toList
  }

  def queryInt(sql: SQLWithArgs): Int = {
    val prepared = conn.prepareStatement(sql.sql)
    if(sql.args != null) setStatementArgs(prepared, sql.args)

    LOG.debug("SQL Preparing: {} args: {}", Seq(sql.sql, sql.args):_*)

    val rs = prepared.executeQuery()

    if(rs.next) {
      rs.getInt(1)
    } else throw new IllegalArgumentException("query return no rows")
  }

}