#!/usr/bin/perl

use strict;
use warnings;
use DynaLoader ();
use File::Basename qw(basename);
use File::Spec;

sub fail {
  my ($msg) = @_;
  print STDERR "$msg\n";
  exit 1;
}

sub load_framework_binary {
  my ($framework_dir) = @_;
  my $name = basename($framework_dir);
  $name =~ s/\.framework$//;
  my $bin = File::Spec->catfile($framework_dir, $name);
  fail("Framework binary not found: $bin") unless -f $bin;
  my $handle = DynaLoader::dl_load_file($bin, 0)
    or fail("Failed to load framework binary: $bin");
  return $handle;
}

sub install_xsub {
  my ($handle, $symbol_name, $alias) = @_;
  my $symbol = DynaLoader::dl_find_symbol($handle, $symbol_name)
    or fail("Symbol '$symbol_name' not found in framework");
  DynaLoader::dl_install_xsub($alias, $symbol);
}

sub call_alias {
  my ($alias) = @_;
  no strict 'refs';
  &{$alias}();
}

my $framework_path = shift @ARGV;
fail("Missing framework path") unless defined $framework_path;
my $maybe_test_client = $ARGV[0] // "";
if ($maybe_test_client =~ m{/}) {
  $ENV{MEDIAREMOTEADAPTER_TEST_CLIENT_PATH} = shift @ARGV;
}
my $command = shift @ARGV;
fail("Missing command") unless defined $command;

fail("Expected a .framework path") unless $framework_path =~ /\.framework$/;
fail("Framework path does not exist: $framework_path") unless -d $framework_path;

my $handle = load_framework_binary($framework_path);

if ($command eq "get") {
  my $opt = shift @ARGV;
  if (defined $opt && $opt ne "--now") {
    fail("Unsupported get option: $opt");
  }
  fail("Too many arguments for get") if defined shift @ARGV;
  install_xsub($handle, "adapter_get_env", "main::adapter_get_env");
  call_alias("main::adapter_get_env");
  exit 0;
}

if ($command eq "send") {
  my $id = shift @ARGV;
  fail("Missing command id for send") unless defined $id;
  fail("Too many arguments for send") if defined shift @ARGV;
  $ENV{MEDIAREMOTEADAPTER_PARAM_adapter_send_0_command} = "$id";
  install_xsub($handle, "adapter_send_env", "main::adapter_send_env");
  call_alias("main::adapter_send_env");
  exit 0;
}

if ($command eq "seek") {
  my $position = shift @ARGV;
  fail("Missing position for seek") unless defined $position;
  fail("Too many arguments for seek") if defined shift @ARGV;
  $ENV{MEDIAREMOTEADAPTER_PARAM_adapter_seek_0_position} = "$position";
  install_xsub($handle, "adapter_seek_env", "main::adapter_seek_env");
  call_alias("main::adapter_seek_env");
  exit 0;
}

if ($command eq "test") {
  fail("Too many arguments for test") if defined shift @ARGV;
  install_xsub($handle, "adapter_test", "main::adapter_test");
  call_alias("main::adapter_test");
  exit 0;
}

fail("Unsupported command: $command");
