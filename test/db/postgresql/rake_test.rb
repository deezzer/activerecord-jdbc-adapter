require 'rake_test_support'
require 'db/postgres'
require 'db/postgresql/test_helper'

class PostgresRakeTest < Test::Unit::TestCase
  include RakeTestSupport

  def db_config
    POSTGRES_CONFIG
  end

  def do_teardown
    drop_rake_test_database(:silence)
  end

  PSQL_EXE = which('psql')

  test 'rake db:create (and db:drop)' do
    Rake::Task["db:create"].invoke
    if PSQL_EXE
      output = psql("-d template1 -c '\\\l'")
      assert_match(/#{db_name}/m, output)
    end

    Rake::Task["db:drop"].invoke
    if PSQL_EXE
      output = psql("-d template1 -c '\\\l'")
      assert_no_match(/#{db_name}/m, output)
    end
  end

  test 'rake db:drop (non-existing database)' do
    drop_rake_test_database(:silence)
    with_disabled_jdbc_driver_logging do
      Rake::Task["db:drop"].invoke
    end
  end

  test 'rake db:test:purge' do
    # Rake::Task["db:create"].invoke
    create_rake_test_database do |connection|
      connection.create_table('users') { |t| t.string :name }
    end

    Rake::Task["db:test:purge"].invoke

    ActiveRecord::Base.establish_connection db_config.merge :database => db_name
    assert_false ActiveRecord::Base.connection.table_exists?('users')
    ActiveRecord::Base.connection.disconnect!
  end

  test 'rake db:structure:dump and db:structure:load' do
    omit('pg_dump not available') unless self.class.which('pg_dump')
    # Rake::Task["db:create"].invoke
    create_rake_test_database do |connection|
      create_schema_migrations_table(connection)
      connection.create_table('users') { |t| t.string :name; t.timestamps }
    end

    structure_sql = File.join('db', structure_sql_filename)
    begin
      Dir.mkdir 'db' # db/structure.sql
      Rake::Task["db:structure:dump"].invoke

      assert File.exists?(structure_sql)
      assert_match(/CREATE TABLE users/, File.read(structure_sql))

      # db:structure:load
      drop_rake_test_database(:silence)
      create_rake_test_database

      # Need to establish a connection manually here because the
      # environment and load_config tasks don't run to set up the
      # connection because they were already ran when dumping the structure
      with_connection db_config.merge(database: db_name) do |_connection|
        Rake::Task["db:structure:load"].invoke
      end

      with_connection db_config.merge(database: db_name) do |connection|
        assert connection.table_exists?('users')
      end
    ensure
      File.delete(structure_sql) if File.exists?(structure_sql)
      Dir.rmdir 'db'
    end
  end

  setup { rm_r 'db' if File.exist?('db') }

  test 'rake db:charset' do
    create_rake_test_database
    expect_rake_output 'UTF8'
    Rake::Task["db:charset"].invoke
  end

  test 'rake db:collation' do
    create_rake_test_database
    expect_rake_output(/\w*?\.UTF-8/) # en_US.UTF-8
    Rake::Task["db:collation"].invoke
  end

  private

  def psql(args)
    args = args.join(' ') unless args.is_a?(String)
    if db_config[:host] != 'localhost'
      args = "--host=#{db_config[:host]} #{args}"
    end
    if username = ENV['PSQL_USERNAME']
      args = "--username=#{username} #{args}"
    end
    `#{PSQL_EXE} #{args}`
  end

end
