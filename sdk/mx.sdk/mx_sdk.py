#
# Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# The Universal Permissive License (UPL), Version 1.0
#
# Subject to the condition set forth below, permission is hereby granted to any
# person obtaining a copy of this software, associated documentation and/or
# data (collectively the "Software"), free of charge and under any and all
# copyright rights in the Software, and any and all patent rights owned or
# freely licensable by each licensor hereunder covering either (i) the
# unmodified Software as contributed to or provided by such licensor, or (ii)
# the Larger Works (as defined below), to deal in both
#
# (a) the Software, and
#
# (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
# one is included with the Software each a "Larger Work" to which the Software
# is contributed by such licensors),
#
# without restriction, including without limitation the rights to copy, create
# derivative works of, display, perform, and distribute the Software and make,
# use, sell, offer for sale, import, export, have made, and have sold the
# Software and the Larger Work(s), and to sublicense the foregoing rights on
# either these or other terms.
#
# This license is subject to the following condition:
#
# The above copyright notice and either this complete permission notice or at a
# minimum a reference to the UPL must be included in all copies or substantial
# portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.
#

from abc import ABCMeta

import mx
import mx_gate
import mx_subst
import datetime
import os

from mx_gate import Task
from mx_unittest import unittest
from os.path import join, exists, isfile, isdir
from mx_javamodules import as_java_module

def _with_metaclass(meta, *bases):
    """Create a base class with a metaclass."""

    # Copyright (c) 2010-2018 Benjamin Peterson
    # Taken from six, Python compatibility library
    # MIT license

    # This requires a bit of explanation: the basic idea is to make a dummy
    # metaclass for one level of class instantiation that replaces itself with
    # the actual metaclass.
    class MetaClass(type):

        def __new__(mcs, name, this_bases, d):
            return meta(name, bases, d)

        @classmethod
        def __prepare__(mcs, name, this_bases):
            return meta.__prepare__(name, bases)
    return type.__new__(MetaClass, '_with_metaclass({}, {})'.format(meta, bases), (), {}) #pylint: disable=unused-variable

_suite = mx.suite('sdk')

graalvm_hostvm_configs = [
    ('jvm', [], ['--jvm'], 50),
    ('native', [], ['--native'], 100)
]


def _sdk_gate_runner(args, tasks):
    with Task('SDK UnitTests', tasks, tags=['test']) as t:
        if t: unittest(['--suite', 'sdk', '--enable-timing', '--verbose', '--fail-fast'])
    with Task('Check Copyrights', tasks) as t:
        if t: mx.checkcopyrights(['--primary'])

mx_gate.add_gate_runner(_suite, _sdk_gate_runner)

def build_oracle_compliant_javadoc_args(suite, product_name, feature_name):
    """
    :type product_name: str
    :type feature_name: str
    """
    version = suite.release_version()
    revision = suite.vc.parent(suite.vc_dir)
    copyright_year = str(datetime.datetime.fromtimestamp(suite.vc.parent_info(suite.vc_dir)['committer-ts']).year)
    return ['--arg', '@-header', '--arg', '<b>%s %s Java API Reference<br>%s</b><br>%s' % (product_name, feature_name, version, revision),
            '--arg', '@-bottom', '--arg', '<center>Copyright &copy; 2012, %s, Oracle and/or its affiliates. All rights reserved.</center>' % (copyright_year),
            '--arg', '@-windowtitle', '--arg', '%s %s Java API Reference' % (product_name, feature_name)]

def javadoc(args):
    """build the Javadoc for all API packages"""
    extraArgs = build_oracle_compliant_javadoc_args(_suite, 'GraalVM', 'SDK')
    mx.javadoc(['--unified', '--exclude-packages', 'org.graalvm.polyglot.tck'] + extraArgs + args)

def add_graalvm_hostvm_config(name, java_args=None, launcher_args=None, priority=0):
    """
    :type name: str
    :type java_args: list[str] | None
    :type launcher_args: list[str] | None
    :type priority: int
    """
    graalvm_hostvm_configs.append((name, java_args, launcher_args, priority))


class AbstractNativeImageConfig(_with_metaclass(ABCMeta, object)):
    def __init__(self, destination, jar_distributions, build_args, links=None, is_polyglot=False, dir_jars=False): # pylint: disable=super-init-not-called
        """
        :type destination: str
        :type jar_distributions: list[str]
        :type build_args: list[str]
        :type links: list[str]
        :param bool dir_jars: If true, all jars in the component directory are added to the classpath.
        """
        self.destination = mx_subst.path_substitutions.substitute(destination)
        self.jar_distributions = jar_distributions
        self.build_args = build_args
        self.links = [mx_subst.path_substitutions.substitute(link) for link in links] if links else []
        self.is_polyglot = is_polyglot
        self.dir_jars = dir_jars

        assert isinstance(self.jar_distributions, list)
        assert isinstance(self.build_args, list)

    def __str__(self):
        return self.destination

    def __repr__(self):
        return str(self)


class LauncherConfig(AbstractNativeImageConfig):
    def __init__(self, destination, jar_distributions, main_class, build_args, links=None, is_main_launcher=True,
                 default_symlinks=True, is_sdk_launcher=False, is_polyglot=False, custom_bash_launcher=None,
                 dir_jars=False, extra_jvm_args=None):
        """
        :param custom_bash_launcher: Uses custom bash launcher, unless compiled as native image
        :type main_class: str
        :type default_symlinks: bool
        :type custom_bash_launcher: str
        """
        super(LauncherConfig, self).__init__(destination, jar_distributions, build_args, links, is_polyglot, dir_jars)
        self.main_class = main_class
        self.is_main_launcher = is_main_launcher
        self.default_symlinks = default_symlinks
        self.is_sdk_launcher = is_sdk_launcher
        self.custom_bash_launcher = custom_bash_launcher
        self.extra_jvm_args = [] if extra_jvm_args is None else extra_jvm_args


class LanguageLauncherConfig(LauncherConfig):
    def __init__(self, destination, jar_distributions, main_class, build_args, language, links=None, is_main_launcher=True,
                 default_symlinks=True, is_sdk_launcher=True, custom_bash_launcher=None, dir_jars=False):
        super(LanguageLauncherConfig, self).__init__(destination, jar_distributions, main_class, build_args, links,
                                                     is_main_launcher, default_symlinks, is_sdk_launcher, False,
                                                     custom_bash_launcher, dir_jars)
        self.language = language


class LibraryConfig(AbstractNativeImageConfig):
    def __init__(self, destination, jar_distributions, build_args, links=None, jvm_library=False, is_polyglot=False, dir_jars=False):
        """
        :type jvm_library: bool
        """
        super(LibraryConfig, self).__init__(destination, jar_distributions, build_args, links, is_polyglot, dir_jars)
        self.jvm_library = jvm_library


class GraalVmComponent(object):
    def __init__(self, suite, name, short_name, license_files, third_party_license_files,
                 jar_distributions=None, builder_jar_distributions=None, support_distributions=None,
                 dir_name=None, launcher_configs=None, library_configs=None, provided_executables=None,
                 polyglot_lib_build_args=None, polyglot_lib_jar_dependencies=None, polyglot_lib_build_dependencies=None,
                 has_polyglot_lib_entrypoints=False,
                 boot_jars=None, priority=None, installable=False, post_install_msg=None, installable_id=None):
        """
        :param suite mx.Suite: the suite this component belongs to
        :type name: str
        :param str short_name: a short, unique name for this component
        :param str | None | False dir_name: the directory name in which this component lives. If `None`, the `short_name` is used. If `False`, files are copied to the root-dir for the component type.
        :param installable: Produce a distribution installable via `gu`
        :param post_install_msg: Post-installation message to be printed
        :type license_files: list[str]
        :type third_party_license_files: list[str]
        :type provided_executables: list[str]
        :type polyglot_lib_build_args: list[str]
        :type polyglot_lib_jar_dependencies: list[str]
        :type polyglot_lib_build_dependencies: list[str]
        :type has_polyglot_lib_entrypoints: bool
        :type boot_jars: list[str]
        :type launcher_configs: list[LauncherConfig]
        :type library_configs: list[LibraryConfig]
        :type jar_distributions: list[str]
        :type builder_jar_distributions: list[str]
        :type support_distributions: list[str]
        :type priority: int
        :type installable: bool
        :type installable_id: str
        :type post_install_msg: str
        """
        self.suite = suite
        self.name = name
        self.short_name = short_name
        self.dir_name = dir_name if dir_name is not None else short_name
        self.license_files = license_files
        self.third_party_license_files = third_party_license_files
        self.provided_executables = provided_executables or []
        self.polyglot_lib_build_args = polyglot_lib_build_args or []
        self.polyglot_lib_jar_dependencies = polyglot_lib_jar_dependencies or []
        self.polyglot_lib_build_dependencies = polyglot_lib_build_dependencies or []
        self.has_polyglot_lib_entrypoints = has_polyglot_lib_entrypoints
        self.boot_jars = boot_jars or []
        self.jar_distributions = jar_distributions or []
        self.builder_jar_distributions = builder_jar_distributions or []
        self.support_distributions = support_distributions or []
        self.priority = priority or 0
        """ priority with a higher value means higher priority """
        self.launcher_configs = launcher_configs or []
        self.library_configs = library_configs or []
        self.installable = installable
        self.post_install_msg = post_install_msg
        self.installable_id = installable_id or self.dir_name

        assert isinstance(self.jar_distributions, list)
        assert isinstance(self.builder_jar_distributions, list)
        assert isinstance(self.support_distributions, list)
        assert isinstance(self.license_files, list)
        assert isinstance(self.third_party_license_files, list)
        assert isinstance(self.provided_executables, list)
        assert isinstance(self.polyglot_lib_build_args, list)
        assert isinstance(self.polyglot_lib_jar_dependencies, list)
        assert isinstance(self.polyglot_lib_build_dependencies, list)
        assert isinstance(self.boot_jars, list)
        assert isinstance(self.launcher_configs, list)
        assert isinstance(self.library_configs, list)

    def __str__(self):
        return "{} ({})".format(self.name, self.dir_name)


class GraalVmTruffleComponent(GraalVmComponent):
    def __init__(self, suite, name, short_name, license_files, third_party_license_files, truffle_jars,
                 builder_jar_distributions=None, support_distributions=None, dir_name=None, launcher_configs=None,
                 library_configs=None, provided_executables=None, polyglot_lib_build_args=None,
                 polyglot_lib_jar_dependencies=None, polyglot_lib_build_dependencies=None,
                 has_polyglot_lib_entrypoints=False, boot_jars=None, include_in_polyglot=True, priority=None,
                 installable=False, post_install_msg=None, standalone_dir_name=None, installable_id=None):
        """
        :param truffle_jars: JAR distributions that should be on the classpath for the language implementation.
        :param include_in_polyglot: whether this component is included in `--language:all` or `--tool:all` and should be part of polyglot images.
        :type truffle_jars: list[str]
        :type include_in_polyglot: bool
        :type standalone_dir_name: str
        """
        super(GraalVmTruffleComponent, self).__init__(suite, name, short_name, license_files, third_party_license_files,
                                                      truffle_jars, builder_jar_distributions, support_distributions,
                                                      dir_name, launcher_configs, library_configs, provided_executables,
                                                      polyglot_lib_build_args, polyglot_lib_jar_dependencies,
                                                      polyglot_lib_build_dependencies, has_polyglot_lib_entrypoints,
                                                      boot_jars, priority, installable, post_install_msg, installable_id)
        self.include_in_polyglot = include_in_polyglot
        self.standalone_dir_name = standalone_dir_name or '{}-<version>-<graalvm_os>-<arch>'.format(self.dir_name)
        assert isinstance(self.include_in_polyglot, bool)


class GraalVmLanguage(GraalVmTruffleComponent):
    pass


class GraalVmTool(GraalVmTruffleComponent):
    def __init__(self, suite, name, short_name, license_files, third_party_license_files, truffle_jars,
                 builder_jar_distributions=None, support_distributions=None, dir_name=None, launcher_configs=None,
                 library_configs=None, provided_executables=None, polyglot_lib_build_args=None,
                 polyglot_lib_jar_dependencies=None, polyglot_lib_build_dependencies=None,
                 has_polyglot_lib_entrypoints=False, boot_jars=None, include_in_polyglot=True, include_by_default=False,
                 priority=None, installable=False, post_install_msg=None, installable_id=None):
        super(GraalVmTool, self).__init__(suite,
                                          name,
                                          short_name,
                                          license_files,
                                          third_party_license_files,
                                          truffle_jars,
                                          builder_jar_distributions,
                                          support_distributions,
                                          dir_name,
                                          launcher_configs,
                                          library_configs,
                                          provided_executables,
                                          polyglot_lib_build_args,
                                          polyglot_lib_jar_dependencies,
                                          polyglot_lib_build_dependencies,
                                          has_polyglot_lib_entrypoints,
                                          boot_jars,
                                          include_in_polyglot,
                                          priority,
                                          installable,
                                          post_install_msg,
                                          installable_id)
        self.include_by_default = include_by_default


class GraalVMSvmMacro(GraalVmComponent):
    pass


class GraalVmJdkComponent(GraalVmComponent):
    pass


class GraalVmJreComponent(GraalVmComponent):
    pass


class GraalVmJvmciComponent(GraalVmJreComponent):
    def __init__(self, suite, name, short_name, license_files, third_party_license_files, jvmci_jars,
                 jar_distributions=None, builder_jar_distributions=None, support_distributions=None,
                 graal_compiler=None, dir_name=None, launcher_configs=None, library_configs=None,
                 provided_executables=None, polyglot_lib_build_args=None, polyglot_lib_jar_dependencies=None,
                 polyglot_lib_build_dependencies=None, has_polyglot_lib_entrypoints=False, boot_jars=None,
                 priority=None, installable=False, post_install_msg=None, installable_id=None):
        """
        :type jvmci_jars: list[str]
        :type graal_compiler: str
        """
        super(GraalVmJvmciComponent, self).__init__(suite,
                                                    name,
                                                    short_name,
                                                    license_files,
                                                    third_party_license_files,
                                                    jar_distributions,
                                                    builder_jar_distributions,
                                                    support_distributions,
                                                    dir_name,
                                                    launcher_configs,
                                                    library_configs,
                                                    provided_executables,
                                                    polyglot_lib_build_args,
                                                    polyglot_lib_jar_dependencies,
                                                    polyglot_lib_build_dependencies,
                                                    has_polyglot_lib_entrypoints,
                                                    boot_jars,
                                                    priority,
                                                    installable,
                                                    post_install_msg,
                                                    installable_id)

        self.graal_compiler = graal_compiler
        self.jvmci_jars = jvmci_jars or []

        assert isinstance(self.jvmci_jars, list)


_graalvm_components = dict()


def register_graalvm_component(component):
    """
    :type component: GraalVmComponent
    :type suite: mx.Suite
    """
    def _log_ignored_component(kept, ignored):
        """
        :type kept: GraalVmComponent
        :type ignored: GraalVmComponent
        """
        mx.logv('Suites \'{}\' and \'{}\' are registering a component with the same short name (\'{}\'), with priority \'{}\' and \'{}\' respectively.'.format(kept.suite.name, ignored.suite.name, kept.short_name, kept.priority, ignored.priority))
        mx.logv('Ignoring the one from suite \'{}\'.'.format(ignored.suite.name))

    _prev = _graalvm_components.get(component.short_name, None)
    if _prev:
        if _prev.priority == component.priority:
            mx.abort('Suites \'{}\' and \'{}\' are registering a component with the same short name (\'{}\') and priority (\'{}\')'.format(_prev.suite.name, component.suite.name, _prev.short_name, _prev.priority))
        elif _prev.priority < component.priority:
            _graalvm_components[component.short_name] = component
            _log_ignored_component(component, _prev)
        else:
            _log_ignored_component(_prev, component)
    else:
        _graalvm_components[component.short_name] = component


def graalvm_components(opt_limit_to_suite=False):
    """
    :rtype: list[GraalVmComponent]
    """
    if opt_limit_to_suite and mx.get_opts().specific_suites:
        return [c for c in _graalvm_components.values() if c.suite.name in mx.get_opts().specific_suites]
    else:
        return list(_graalvm_components.values())

def jdk_enables_jvmci_by_default(jdk):
    """
    Gets the default value for the EnableJVMCI VM option in `jdk`.
    """
    if not hasattr(jdk, '.enables_jvmci_by_default'):
        out = mx.LinesOutputCapture()
        sink = lambda x: x
        mx.run([jdk.java, '-XX:+UnlockExperimentalVMOptions', '-XX:+PrintFlagsFinal', '-version'], out=out, err=sink)
        setattr(jdk, '.enables_jvmci_by_default', any('EnableJVMCI' in line and 'true' in line for line in out.lines))
    return getattr(jdk, '.enables_jvmci_by_default')

def jlink_new_jdk(jdk, dst_jdk_dir, module_dists, root_module_names=None):
    """
    Uses jlink from `jdk` to create a new JDK image in `dst_jdk_dir` with `module_dists` and
    their dependencies added to the JDK image, replacing any existing modules of the same name.

    :param JDKConfig jdk: source JDK
    :param str dst_jdk_dir: path to use for the jlink --output option
    :param list module_dists: list of distributions defining modules
    :param list root_module_names: list of strings naming the module root set for the new JDK image.
                     The named modules must either be in `module_dists` or in `jdk`. If None, then
                     the root set will be all the modules in ``module_dists` and `jdk`.

    """
    if jdk.javaCompliance < '9':
        mx.abort('Cannot derive a new JDK from ' + jdk.home + ' with jlink since it is not JDK 9 or later')

    exploded_java_base_module = join(jdk.home, 'modules', 'java.base')
    if exists(exploded_java_base_module):
        mx.abort('Cannot derive a new JDK from ' + jdk.home + ' since it appears to be a developer build with exploded modules')

    jimage = join(jdk.home, 'lib', 'modules')
    jmods = join(jdk.home, 'jmods')
    if not isfile(jimage):
        mx.abort('Cannot derive a new JDK from ' + jdk.home + ' since ' + jimage + ' is missing or is not an ordinary file')
    if not isdir(jmods):
        mx.abort('Cannot derive a new JDK from ' + jdk.home + ' since ' + jmods + ' is missing or is not a directory')

    modules = [as_java_module(dist, jdk) for dist in module_dists]
    all_module_names = frozenset([m.name for m in jdk.get_modules()] + [m.name for m in modules])

    jlink = [mx.exe_suffix(join(jdk.home, 'bin', 'jlink'))]
    if jdk_enables_jvmci_by_default(jdk):
        # On JDK 9+, +EnableJVMCI forces jdk.internal.vm.ci to be in the root set
        jlink.append('-J-XX:-EnableJVMCI')
    if root_module_names is not None:
        missing = frozenset(root_module_names) - all_module_names
        if missing:
            mx.abort('Invalid module(s): {}.\nAvailable modules: {}'.format(','.join(missing), ','.join(sorted(all_module_names))))
        jlink.append('--add-modules=' + ','.join(root_module_names))
    else:
        jlink.append('--add-modules=' + ','.join(sorted(all_module_names)))
    module_path = jmods
    if module_dists:
        module_path = os.pathsep.join((m.jarpath for m in modules))  + os.pathsep + module_path
    jlink.append('--module-path=' + module_path)
    jlink.append('--output=' + dst_jdk_dir)

    # These options are inspired by how OpenJDK runs jlink to produce the final runtime image.
    jlink.extend(['-J-XX:+UseSerialGC', '-J-Xms32M', '-J-Xmx512M', '-J-XX:TieredStopAtLevel=1'])
    jlink.append('-J-Dlink.debug=true')
    jlink.append('--dedup-legal-notices=error-if-not-same-content')
    jlink.append('--keep-packaged-modules=' + join(dst_jdk_dir, 'jmods'))

    # TODO: investigate the options below used by OpenJDK to see if they should be used:
    # --release-info: this allow extra properties to be written to the <jdk>/release file
    # --order-resources: specifies order of resources in generated lib/modules file.
    #       This is apparently not so important if a CDS archive is available.
    # --generate-jli-classes: pre-generates a set of java.lang.invoke classes.
    #       See https://github.com/openjdk/jdk/blob/master/make/GenerateLinkOptData.gmk
    mx.run(jlink)

    # Create CDS archive (https://openjdk.java.net/jeps/341).
    out = mx.OutputCapture()
    if mx.run([mx.exe_suffix(join(dst_jdk_dir, 'bin', 'java')), '-Xshare:dump', '-Xmx128M', '-Xms128M'], out=out, err=out, nonZeroIsFatal=False) != 0:
        mx.log(out.data)
        mx.abort('Error generating CDS shared archive')

mx.update_commands(_suite, {
    'javadoc': [javadoc, '[SL args|@VM options]'],
})
