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
package org.apache.ibatis.reflection;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.ReflectPermission;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

/**
 * Reflector是 MyBatis 中反射模块的基础，每个 Reflector对象都对应一个类，在 Reflector 中 缓存了反射操作需要使用的类的元信息。
 *
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 *
 * @author Clinton Begin
 */
public class Reflector {

  // 对应的 Class 类型
  private final Class<?> type;
  // 可读属性的名称集合，可读属性就是存在相应 getter 方法的属性
  private final String[] readablePropertyNames;
  // 可写属性的名称集合，可写属性就是存在相应 setter 方法的属性
  private final String[] writablePropertyNames;
  // 记录了属性相应的 setter 方法 ， key 是属性名称， value 是 Invoker 对象，它是对 setter 方法对应 Method 对象 的封装
  private final Map<String, Invoker> setMethods = new HashMap<>();
  // 属性相应的 getter 方法集合 ， key 是属性名称， value 也是 Invoker 对象
  private final Map<String, Invoker> getMethods = new HashMap<>();
  // 记录了属性相应的 setter 方法 的参数值类型， key 是属性名称， value 是 setter 方法的参数类型
  private final Map<String, Class<?>> setTypes = new HashMap<>();
  // 记录了属性相应的 getter 方法的返回位类型， key是属性名称， value 是 getter 方法的返回位类型
  private final Map<String, Class<?>> getTypes = new HashMap<>();
  // 记录了默认构造方法
  private Constructor<?> defaultConstructor;
  // 记录了所有属性名称的集合
  private Map<String, String> caseInsensitivePropertyMap = new HashMap<>();

  public Reflector(Class<?> clazz) {
    type = clazz;
    // 查找 clazz 的默认构造方法(元参构造方法)， 具体实现是通过反射遥历所有构造方法
    addDefaultConstructor(clazz);
    // 处理 clazz 中的 getter 方法，填充 getMethods 集合和 getTypes 集合
    addGetMethods(clazz);
    // 处理 clazz 中的 setter 方法，填充 setMethods 集合和 setTypes 集合
    addSetMethods(clazz);
    // 处理没有 getterI setter 方法的字段
    addFields(clazz);
    // 根据 getMethodslsetMethods 集合 ，初始化可读 /写属性的名称集合
    readablePropertyNames = getMethods.keySet().toArray(new String[getMethods.keySet().size()]);
    writablePropertyNames = setMethods.keySet().toArray(new String[setMethods.keySet().size()]);
    // 初始化 caseinsensitivePropertyMap 集合，其中记录了所有大写格式的属性名称
    for (String propName : readablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
    for (String propName : writablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
  }

  private void addDefaultConstructor(Class<?> clazz) {
    Constructor<?>[] consts = clazz.getDeclaredConstructors();
    for (Constructor<?> constructor : consts) {
      if (constructor.getParameterTypes().length == 0) {
        this.defaultConstructor = constructor;
      }
    }
  }

  private void addGetMethods(Class<?> cls) {
    // conflictingGetters 集合的 key为属性名称， value是相应 getter方法集合，因为子类可能覆盖父类的 getter 方法，
    // 所以同一属性名称可能会存在多个 getter 方法
    Map<String, List<Method>> conflictingGetters = new HashMap<>();
    // 步骤 1: 获取指定类以及其父类和接口中定义的方法
    Method[] methods = getClassMethods(cls);
    // 步骤 2:按照 JavaBean 规范查找 getter 方法，并记录到 conflictingGetters 集合中
    for (Method method : methods) {
      // getter 方法的参数列表不为空则不符合 getter 定义
      if (method.getParameterTypes().length > 0) {
        continue;
      }
      String name = method.getName();
      // JavaBean 中 getter 方法的方法名长度大于 3 且必须以 ”get”开头，或者是“is”开头并且长度大于2
      if ((name.startsWith("get") && name.length() > 3)
          || (name.startsWith("is") && name.length() > 2)) {
        // 按照 JavaBean 的规范，获取对应的属性名称
        name = PropertyNamer.methodToProperty(name);
        // 将属性名与 getter 方法的对反关系记录到 conflictingGetters 集合中
        addMethodConflict(conflictingGetters, name, method);
      }
    }
    resolveGetterConflicts(conflictingGetters);
  }

  private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
    for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
      Method winner = null;
      String propName = entry.getKey();
      // 同一属性名称存在多个 getter 方法，则需要比较这些 getter 方法的返回值，选择 getter 方法迭代过程中的临时变量，
      // 用于记录迭代到目前为止 ，最适合作为 getter 方法的 Method
      for (Method candidate : entry.getValue()) {
        if (winner == null) {
          winner = candidate;
          continue;
        }
        // 返回值类型
        Class<?> winnerType = winner.getReturnType();
        Class<?> candidateType = candidate.getReturnType();
        // 返回值相同，这种情况应该在前一步骤中被过滤掉，如果这里出现了且还不是boolean类型的，则抛出异常
        if (candidateType.equals(winnerType)) {
          if (!boolean.class.equals(candidateType)) {
            throw new ReflectionException(
                "Illegal overloaded getter method with ambiguous type for property "
                    + propName + " in class " + winner.getDeclaringClass()
                    + ". This breaks the JavaBeans specification and can cause unpredictable results.");
          } else if (candidate.getName().startsWith("is")) {
            winner = candidate;
          }
        } else if (candidateType.isAssignableFrom(winnerType)) {
          // OK getter type is descendant
          // 当前最适合的方法的返回佳是当前方法返回佳的子类，什么都不做，当前最适合的方法依然不变
        } else if (winnerType.isAssignableFrom(candidateType)) {
          // 当前方法的返回位是当前最适合的方法的返回值的子类，更新临时交量 getter，当前的getter 方法成为最适合的 getter 方法
          winner = candidate;
        } else {
          // 不知道什么情况……抛出异常
          throw new ReflectionException(
              "Illegal overloaded getter method with ambiguous type for property "
                  + propName + " in class " + winner.getDeclaringClass()
                  + ". This breaks the JavaBeans specification and can cause unpredictable results.");
        }
      }
      addGetMethod(propName, winner);
    }
  }

  private void addGetMethod(String name, Method method) {
    // 检测属性名是否合法
    if (isValidPropertyName(name)) {
      // 将属性名以及对应的 MethodInvoker 对象添加到 getMethods 集合中
      getMethods.put(name, new MethodInvoker(method));
      // 获取返回值的 Type
      Type returnType = TypeParameterResolver.resolveReturnType(method, type);
      // 将属性名称及其 getter 方法的返回位类型添加到 getTypes 集合中保存
      getTypes.put(name, typeToClass(returnType));
    }
  }

  private void addSetMethods(Class<?> cls) {
    Map<String, List<Method>> conflictingSetters = new HashMap<>();
    Method[] methods = getClassMethods(cls);
    for (Method method : methods) {
      String name = method.getName();
      if (name.startsWith("set") && name.length() > 3) {
        if (method.getParameterTypes().length == 1) {
          name = PropertyNamer.methodToProperty(name);
          addMethodConflict(conflictingSetters, name, method);
        }
      }
    }
    resolveSetterConflicts(conflictingSetters);
  }

  private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
    List<Method> list = conflictingMethods.computeIfAbsent(name, k -> new ArrayList<>());
    list.add(method);
  }

  private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
    for (String propName : conflictingSetters.keySet()) {
      List<Method> setters = conflictingSetters.get(propName);
      Class<?> getterType = getTypes.get(propName);
      Method match = null;
      ReflectionException exception = null;
      for (Method setter : setters) {
        Class<?> paramType = setter.getParameterTypes()[0];
        if (paramType.equals(getterType)) {
          // should be the best match
          match = setter;
          break;
        }
        if (exception == null) {
          try {
            match = pickBetterSetter(match, setter, propName);
          } catch (ReflectionException e) {
            // there could still be the 'best match'
            match = null;
            exception = e;
          }
        }
      }
      if (match == null) {
        throw exception;
      } else {
        addSetMethod(propName, match);
      }
    }
  }

  private Method pickBetterSetter(Method setter1, Method setter2, String property) {
    if (setter1 == null) {
      return setter2;
    }
    Class<?> paramType1 = setter1.getParameterTypes()[0];
    Class<?> paramType2 = setter2.getParameterTypes()[0];
    if (paramType1.isAssignableFrom(paramType2)) {
      return setter2;
    } else if (paramType2.isAssignableFrom(paramType1)) {
      return setter1;
    }
    throw new ReflectionException("Ambiguous setters defined for property '" + property + "' in class '"
        + setter2.getDeclaringClass() + "' with types '" + paramType1.getName() + "' and '"
        + paramType2.getName() + "'.");
  }

  private void addSetMethod(String name, Method method) {
    if (isValidPropertyName(name)) {
      setMethods.put(name, new MethodInvoker(method));
      Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
      setTypes.put(name, typeToClass(paramTypes[0]));
    }
  }

  private Class<?> typeToClass(Type src) {
    Class<?> result = null;
    if (src instanceof Class) {
      result = (Class<?>) src;
    } else if (src instanceof ParameterizedType) {
      result = (Class<?>) ((ParameterizedType) src).getRawType();
    } else if (src instanceof GenericArrayType) {
      Type componentType = ((GenericArrayType) src).getGenericComponentType();
      if (componentType instanceof Class) {
        result = Array.newInstance((Class<?>) componentType, 0).getClass();
      } else {
        Class<?> componentClass = typeToClass(componentType);
        result = Array.newInstance(componentClass, 0).getClass();
      }
    }
    if (result == null) {
      result = Object.class;
    }
    return result;
  }

  // Reflector.addFields()方法会处理类中定义的所有字段，并且将处理后的字段信息添加到 setMethods 集合、 setTypes 集合、
  // getMethods 集合以及 getTypes 集合中，这一点与上面的 Reflector.addGetMethods()方法是一致的
  private void addFields(Class<?> clazz) {
    // 获取 clazz 中定义的全部字段
    Field[] fields = clazz.getDeclaredFields();
    for (Field field : fields) {
      // 当 setMethods 集合不包含同名属性时，将其记录到 setMethods 集合和 setTypes 集合
      if (!setMethods.containsKey(field.getName())) {
        // issue #379 - removed the check for final because JDK 1.5 allows
        // modification of final fields through reflection (JSR-133). (JGB)
        // pr #16 - final static can only be set by the classloader
        int modifiers = field.getModifiers();
        // 过滤掉 final和static修饰的字段
        if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
          addSetField(field);
        }
      }
      if (!getMethods.containsKey(field.getName())) {
        addGetField(field);
      }
    }
    // 处理父类中定义的字段
    if (clazz.getSuperclass() != null) {
      addFields(clazz.getSuperclass());
    }
  }

  private void addSetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      setMethods.put(field.getName(), new SetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      setTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private void addGetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      getMethods.put(field.getName(), new GetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      getTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private boolean isValidPropertyName(String name) {
    return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
  }

  /**
   * This method returns an array containing all methods
   * declared in this class and any superclass.
   * We use this method, instead of the simpler <code>Class.getMethods()</code>,
   * because we want to look for private methods as well.
   *
   * @param cls The class
   * @return An array containing all methods in this class
   */
  private Method[] getClassMethods(Class<?> cls) {
    // 用于记录指定类中定义的全部方法的唯一签名以及对应的 Method 对象
    Map<String, Method> uniqueMethods = new HashMap<>();
    Class<?> currentClass = cls;
    while (currentClass != null && currentClass != Object.class) {
      // 记录 currentClass 这个类中定义的全部方法
      addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

      // we also need to look for interface methods -
      // because the class may be abstract
      // 记录接口中定义的方法
      Class<?>[] interfaces = currentClass.getInterfaces();
      for (Class<?> anInterface : interfaces) {
        addUniqueMethods(uniqueMethods, anInterface.getMethods());
      }

      // 获取父类 ，继续 while 循环
      currentClass = currentClass.getSuperclass();
    }

    Collection<Method> methods = uniqueMethods.values();

    return methods.toArray(new Method[methods.size()]);
  }

  private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
    for (Method currentMethod : methods) {
      if (!currentMethod.isBridge()) {
        String signature = getSignature(currentMethod);
        // check to see if the method is already known
        // if it is known, then an extended class must have
        // overridden a method
        // 检测是否在子类 中已经添加过该方法，如果在子类中已经添加过，则表示子类覆盖了该方法，元须再向 uniqueMethods 集合中添加该方法了
        if (!uniqueMethods.containsKey(signature)) {
          uniqueMethods.put(signature, currentMethod);
        }
      }
    }
  }

  // 通过 Reflector.getSignature () 方法得到的方法签名是:返回值类型#方法名称:参数类型列表 。
  // 例如， Reflector.getSignature(Method)方法的唯一 签名是:java.lang.String#getSignature:] ava.lang.reflect.Method
  // 通过 Reflector . getSignature ( )方法得到的方法签名是全局唯一的，可以作为该方法的唯一标识
  private String getSignature(Method method) {
    StringBuilder sb = new StringBuilder();
    Class<?> returnType = method.getReturnType();
    if (returnType != null) {
      sb.append(returnType.getName()).append('#');
    }
    sb.append(method.getName());
    Class<?>[] parameters = method.getParameterTypes();
    for (int i = 0; i < parameters.length; i++) {
      if (i == 0) {
        sb.append(':');
      } else {
        sb.append(',');
      }
      sb.append(parameters[i].getName());
    }
    return sb.toString();
  }

  /**
   * Checks whether can control member accessible.
   *
   * @return If can control member accessible, it return {@literal true}
   * @since 3.5.0
   */
  public static boolean canControlMemberAccessible() {
    try {
      SecurityManager securityManager = System.getSecurityManager();
      if (null != securityManager) {
        securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
      }
    } catch (SecurityException e) {
      return false;
    }
    return true;
  }

  /**
   * Gets the name of the class the instance provides information for.
   *
   * @return The class name
   */
  public Class<?> getType() {
    return type;
  }

  public Constructor<?> getDefaultConstructor() {
    if (defaultConstructor != null) {
      return defaultConstructor;
    } else {
      throw new ReflectionException("There is no default constructor for " + type);
    }
  }

  public boolean hasDefaultConstructor() {
    return defaultConstructor != null;
  }

  public Invoker getSetInvoker(String propertyName) {
    Invoker method = setMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  public Invoker getGetInvoker(String propertyName) {
    Invoker method = getMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  /**
   * Gets the type for a property setter.
   *
   * @param propertyName - the name of the property
   * @return The Class of the property setter
   */
  public Class<?> getSetterType(String propertyName) {
    Class<?> clazz = setTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets the type for a property getter.
   *
   * @param propertyName - the name of the property
   * @return The Class of the property getter
   */
  public Class<?> getGetterType(String propertyName) {
    Class<?> clazz = getTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets an array of the readable properties for an object.
   *
   * @return The array
   */
  public String[] getGetablePropertyNames() {
    return readablePropertyNames;
  }

  /**
   * Gets an array of the writable properties for an object.
   *
   * @return The array
   */
  public String[] getSetablePropertyNames() {
    return writablePropertyNames;
  }

  /**
   * Check to see if a class has a writable property by name.
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a writable property by the name
   */
  public boolean hasSetter(String propertyName) {
    return setMethods.keySet().contains(propertyName);
  }

  /**
   * Check to see if a class has a readable property by name.
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a readable property by the name
   */
  public boolean hasGetter(String propertyName) {
    return getMethods.keySet().contains(propertyName);
  }

  public String findPropertyName(String name) {
    return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
  }
}
