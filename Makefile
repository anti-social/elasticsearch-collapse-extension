run_in_container = podman run --rm -it \
  --userns keep-id:uid=$$(id -u),gid=$$(id -g) \
  -e GRADLE_USER_HOME=/work/.gradle \
  -v $$(pwd)/cpu.stat:/sys/fs/cgroup/cpu/cpu.stat \
  -v $$(pwd):/work \
  -w /work \
  docker.io/adoptopenjdk/openjdk15 ./gradlew --console=plain $(args)

assemble:
	$(run_in_container) assemble

compile:
	$(run_in_container) compileJava compileTestJava

test:
	$(run_in_container) integTest

check:
	$(run_in_container) check

clean:
	$(run_in_container) clean
