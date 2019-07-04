/**
 *    Copyright 2009-2015 the original author or authors.
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
package org.apache.ibatis.reflection;

/**
 * ReflectorFactory接口主要实现了对 Reflector对象的创建和缓存
 * <p>
 * MyBatis 为该接口提供了 DefaultReflectorFactory这一个实现类
 * 除了使用 MyBatis 提供的 DefaultReflectorFactory 实现，我们还可以在 mybatis-config.xml 中配置自定义的 ReflectorFactory实现类，
 * 从而实现功能上的扩展。
 */
public interface ReflectorFactory {

  // 检测该 ReflectorFactory 对象是否会缓存 Reflector 对象
  boolean isClassCacheEnabled();

  // 设置是否缓存 Reflector 对象
  void setClassCacheEnabled(boolean classCacheEnabled);

  // 创建指定 Class 对应的 Reflector 对象
  Reflector findForClass(Class<?> type);
}