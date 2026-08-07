#!/usr/bin/env ruby

require "json"
require_relative "manual_contract"

inventory_path = ARGV.fetch(0, "build/manual/module-inventory-0.5.0.json")
manifest_path = ARGV.fetch(1, "docs/manual/manifest.yaml")
inventory = JSON.parse(File.read(inventory_path))
errors = ManualDocs::Validator.new(
  inventory: inventory,
  manifest_path: manifest_path,
  repository_root: Dir.pwd,
  expected_release: {
    "ref" => ENV.fetch("MANUAL_RELEASE_REF", "0.5.0"),
    "commit" => ENV.fetch("MANUAL_RELEASE_COMMIT", "664e4dfb544a3c19db484b0f9a8e023a73774b49"),
  },
).errors
abort(errors.join("\n")) unless errors.empty?
puts "Manuals are aligned."
