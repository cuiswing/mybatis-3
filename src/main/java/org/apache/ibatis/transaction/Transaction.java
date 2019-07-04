/**
 *    Copyright 2009-2019 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.transaction;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 在实践中， MyBatis通常会与 Spring集成使用， 数据库的事务是交给 Spring进行管理的。SpringManagedTransaction
 *
 * Wraps a database connection.
 * Handles the connection lifecycle that comprises: its creation, preparation, commit/rollback and close.
 *
 * @author Clinton Begin
 */
public interface Transaction {

  /**
   * 获取对应的数据库连接对象
   * Retrieve inner database connection.
   * @return DataBase connection
   * @throws SQLException
   */
  Connection getConnection() throws SQLException;

  /**
   * 提交事务
   * Commit inner database connection.
   * @throws SQLException
   */
  void commit() throws SQLException;

  /**
   * 回滚事务
   * Rollback inner database connection.
   * @throws SQLException
   */
  void rollback() throws SQLException;

  /**
   * 关闭数据库连接
   * Close inner database connection.
   * @throws SQLException
   */
  void close() throws SQLException;

  /**
   * 获取事务超时时间
   * Get transaction timeout if set.
   * @throws SQLException
   */
  Integer getTimeout() throws SQLException;

}
