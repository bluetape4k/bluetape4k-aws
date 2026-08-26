# 이슈 #570 SNS AOT runtime hints API 마이그레이션 lesson

## 배경

Spring Framework 7.0.8에서 `MemberCategory.INTROSPECT_DECLARED_METHODS`와
`MemberCategory.INTROSPECT_DECLARED_CONSTRUCTORS`가 deprecated 처리됐다.
SNS HTTP adapter는 `SnsHttpEndpointRuntimeHints`와
`SnsControllerMappingReflectiveProcessor`에서 이 enum을 사용했고,
`SnsHttpEndpointAutoConfigurationTest`는 deprecated된
`RuntimeHintsPredicates.reflection().onMethod(...)`를 사용했다.

## 결정 또는 발견 사항

- runtime hints의 타입 등록은 `registerType(type) { typeHint ->
  typeHint.withMembers(MemberCategory.INVOKE_DECLARED_METHODS) }` builder로
  전환했다. 메서드 invocation 권한은 유지하고 deprecated introspection
  category만 제거했다.
- reflective processor의 클래스 경로는 `registerType(element)`로 바꾸고,
  메서드 경로는 같은 builder와 `INVOKE_DECLARED_METHODS`를 사용한다.
  실제 handler method의 `registerMethod(method, ExecutableMode.INVOKE)`와
  `BindingReflectionHintsRegistrar` 호출은 그대로 둔다.
- 테스트 predicate는 `onMethodInvocation(...)`으로 바꿨다. 이 변경은
  controller method와 optional Jackson `readValue(String, Class)`의 invocation
  hint를 계속 검증하며, annotation 수·payload binding·routing 계약은
  변경하지 않는다.

Spring Framework의 [ReflectionHints API 문서][reflection-hints]와
[BindingReflectionHintsRegistrar 문서][binding-hints]를
기준으로 API를 확인했다.

## 결과

deprecated `INTROSPECT_*` enum과 `onMethod(...)` predicate가 SNS AOT 경로에서
사라졌다. 변경 범위는 runtime hints 등록 방식과 테스트 predicate에 한정되며,
8개 composed annotation, controller method/payload binding, optional Jackson
경로와 `FilteredClassLoader` 경계는 기존 테스트로 계속 보호한다.

## 검증

- `:bluetape4k-aws-spring-boot:compileKotlin` 및
  `:bluetape4k-aws-spring-boot:compileTestKotlin`: 통과, migration 후 해당
  소스의 deprecation scan 결과 없음
- `SnsHttpEndpointAutoConfigurationTest`: 13개 테스트 통과
- `:bluetape4k-aws-spring-boot:test`: 770개 테스트 통과, 실패·오류·skip 없음
- `:aws-spring-boot-s3-examples:processAot` 및
  `:aws-spring-boot-s3-examples:processTestAot`: BUILD SUCCESSFUL
- `:bluetape4k-aws-spring-boot:detekt`: BUILD SUCCESSFUL
- `git diff --check`: 통과

이번 수정은 reflection metadata와 predicate API만 바꾸므로 실제 AWS signed
delivery나 emulator 네트워크 smoke는 범위에 포함하지 않았다.

## 향후 지침

Spring Framework AOT API를 올릴 때는 deprecated enum을 기계적으로 되살리지
말고 `ReflectionHints` builder와 `onMethodInvocation(...)` 같은 현재 API를
먼저 확인한다. runtime hints 변경은 반드시 실제 controller method,
payload binding, optional classpath 경계를 확인하는 테스트와 함께 검토한다.

[reflection-hints]: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/aot/hint/ReflectionHints.html
[binding-hints]: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/aot/hint/BindingReflectionHintsRegistrar.html
