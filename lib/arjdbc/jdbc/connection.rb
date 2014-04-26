module ActiveRecord
  module ConnectionAdapters
    # JDBC (connection) base class, custom adapters we support likely extend
    # this class. For maximum performance most of this class and the sub-classes
    # we ship are implemented in Java, check: *RubyJdbcConnection.java*
    class JdbcConnection

      # Initializer implemented in Ruby.
      # @note second argument is mandatory, only optional for compatibility
      def initialize(config, adapter = nil)
        @config = config; @adapter = adapter
        setup_connection_factory
        @connection = nil; init_connection # @see RubyJdbcConnection.init_connection
      rescue Java::JavaSql::SQLException => e
        e = e.cause if defined?(NativeException) && e.is_a?(NativeException) # JRuby-1.6.8
        error = e.getMessage || e.getSQLState
        error = error ? "#{e.java_class.name}: #{error}" : e.java_class.name
        error = ::ActiveRecord::JDBCError.new("The driver encountered an unknown error: #{error}")
        error.errno = e.getErrorCode
        error.sql_exception = e
        raise error
      end

      attr_reader :adapter, :config

      # @deprecated no longer used (pass adapter into #initialize)
      # @see ActiveRecord::ConnectionAdapters::JdbcAdapter#initialize
      def adapter=(adapter); @adapter = adapter; end

      def native_database_types
        JdbcTypeConverter.new(supported_data_types).choose_best_types
      end

      # @deprecated no longer used - only kept for compatibility
      def set_native_database_types
        ArJdbc.deprecate "set_native_database_types is no longer used and does nothing override native_database_types instead"
      end

      # Sets the connection factory from the available configuration.
      # @see #setup_jdbc_factory
      # @see #setup_jndi_factory
      #
      # @note this has nothing to do with the configure_connection implemented
      # on some of the concrete adapters (e.g. {#ArJdbc::Postgres})
      def setup_connection_factory
        if self.class.jndi_config?(config)
          begin
            setup_jndi_factory
          rescue => e
            warn "JNDI data source unavailable: #{e.message}; trying straight JDBC"
            setup_jdbc_factory
          end
        else
          setup_jdbc_factory
        end
      end

    end
  end
end
