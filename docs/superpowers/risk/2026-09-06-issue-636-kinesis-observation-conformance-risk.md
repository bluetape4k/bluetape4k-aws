# Issue #636 Kinesis 관측 conformance 위험 원장

| ID | 위험 | 예방·검증 | 실패 시 조치 |
| --- | --- | --- | --- |
| R-01 | sealed subtype/enum 변경으로 source break | 기존 타입 무변경, additive adapter, ABI check | public 기존 타입 변경 rollback |
| R-02 | Java/Kotlin canonical 구현 drift | root manifest 한 벌과 양 module exact conformance | manifest 또는 양 adapter를 같은 commit에서 수정 |
| R-03 | token 길이 변경으로 dashboard series 단절 | v1 24자, shared hash vector, EN/KO migration | legacy direct tag 유지 후 adapter opt-in 전환 |
| R-04 | shard completion 의미 소실 | Java 한 event→canonical 두 event와 순서 test | expansion mapping 수정, consumer emission은 유지 |
| R-05 | count/cardinality 폭증 | canonical `0..10_000`, exact/over-bound RED | invalid observation fail-closed |
| R-06 | raw identifier/sequence/payload 노출 | canonical field allowlist와 hostile marker negative test | 노출 field 제거 후 재검증 |
| R-07 | test resource가 module별로 달라짐 | 양 source set이 같은 root resource path 참조 | resource 복제를 금지하고 Gradle wiring 복구 |
| R-08 | test resource wiring이 publication을 오염 | publication metadata/POM 생성 검증 | test-only source set 설정 rollback |
| R-09 | CI만 실패하는 lifecycle flake | deterministic fake tests, full clean module tests | race 재현 후 원인 분리, skip 금지 |
| R-10 | 실제 AWS 검증으로 오인 | fake-only/N/A 경계를 review와 PR에 기록 | 실서비스 주장을 제거 |
