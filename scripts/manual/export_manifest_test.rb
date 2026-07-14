require "json"
require "minitest/autorun"
require "tmpdir"

require_relative "export_manifest"

class ExportManifestTest < Minitest::Test
  def test_emits_recursively_sorted_deterministic_json_with_final_lf
    Dir.mktmpdir("manifest-export") do |root|
      source = File.join(root, "manifest.yaml")
      output = File.join(root, "manifest.json")
      File.write(source, <<~YAML)
        modules: []
        releaseCommit: be4e6daea5654f84579955307ec56a58c8f405be
        repository: bluetape4k/bluetape4k-aws
        releaseRef: 0.4.0
        schemaVersion: 2
        title: 한국어 매뉴얼
      YAML
      exporter = ManualDocs::ManifestExporter.new(source_path: source, output_path: output)
      exporter.write
      parsed = JSON.parse(File.read(output))
      assert_equal %w[modules releaseCommit releaseRef repository schemaVersion title], parsed.keys
      assert_equal "bluetape4k/bluetape4k-aws", parsed.fetch("repository")
      assert_equal "한국어 매뉴얼", parsed.fetch("title")
      assert File.binread(output).end_with?("\n")
      assert exporter.current?
    end
  end
end
